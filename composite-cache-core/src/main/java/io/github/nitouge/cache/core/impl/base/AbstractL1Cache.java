package io.github.nitouge.cache.core.impl.base;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.nitouge.cache.core.api.CacheLoader;
import io.github.nitouge.cache.core.api.CacheSyncPolicy;
import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.consts.enums.CacheSyncTypeEnum;
import io.github.nitouge.cache.core.impl.level1.CaffeineCache;
import io.github.nitouge.cache.core.impl.level1.GuavaCache;
import io.github.nitouge.cache.core.metrics.CacheMetricsRecorder;
import io.github.nitouge.cache.core.metrics.NoOpCacheMetricsRecorder;
import io.github.nitouge.cache.core.schedule.ExpiredCacheRefreshSupport;
import io.github.nitouge.cache.core.schedule.NullValueClearTask;
import io.github.nitouge.cache.core.schedule.NullValueClearSupport;
import io.github.nitouge.cache.core.schedule.ExpiredCacheRefreshTask;
import io.github.nitouge.cache.core.sync.listener.CacheSyncMessage;
import io.github.nitouge.cache.core.pool.MdcForkJoinPool;
import io.github.nitouge.cache.core.wrapper.NullValueWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * L1（本地缓存）抽象基类。
 *
 * <p>抽取 {@link CaffeineCache} / {@link GuavaCache} 共用逻辑，消除两者间的重复：
 * <ul>
 *   <li>构造期初始化：NullValue 防穿透簿记缓存 + 过期刷新调度（{@link #init}）</li>
 *   <li>写路径：{@link #put}/{@link #evict}/{@link #clear}/{@link #invalidateCache}</li>
 *   <li>其它：{@link #isExists}/{@link #size}/{@link #keys}/{@link #values} 与同步消息构造</li>
 * </ul>
 *
 * <p>子类只需实现"原生缓存"差异点：一组原子操作（{@code nativePut/nativeInvalidate/...}）、
 * 读路径（{@code get/get(key,callable)/batchGet}）、刷新与 {@code isLoadingCache}。
 *
 */
@Slf4j
public abstract class AbstractL1Cache extends AbstractAdaptingCache implements L1Cache {

    /** 缓存加载器（loading 模式异步加载；manual 模式为 null）。 */
    protected final CacheLoader<Object, Object> cacheLoader;

    /** 缓存同步策略（跨节点 L1 失效广播；可为 null）。 */
    protected final CacheSyncPolicy<?> cacheSyncPolicy;

    /** 记录 NullValue 的 key，用于控制其有效期（内部簿记，统一复用 Caffeine 以保证两种 L1 实现语义一致）。 */
    protected Cache<Object, Integer> nullValueCache;

    /**
     * NullValue 簿记缓存的异步维护线程池（Caffeine 的 removalListener 等回调用）。
     * <p><b>全局共享单例 + 守护线程</b>：避免按 cacheName 各自 {@code new MdcForkJoinPool(...)}（且从不 shutdown、
     * 旧实现还是非守护线程）造成线程/池泄漏——多缓存名场景会累积非守护线程甚至阻止 JVM 退出。
     */
    private static final java.util.concurrent.Executor NULL_VALUE_MAINTENANCE_EXECUTOR = MdcForkJoinPool.sharedPool();

    /**
     * 指标记录器：<b>仅在 L1-only（独立 L1、无组合层）部署</b>时由 CacheManager 注入并记录命中/未命中；
     * 作为 CompositeCache 的内层 L1 时保持 NoOp，避免与组合层重复计数。默认 NoOp。
     */
    private CacheMetricsRecorder metricsRecorder = NoOpCacheMetricsRecorder.INSTANCE;

    public void setMetricsRecorder(CacheMetricsRecorder metricsRecorder) {
        if (metricsRecorder != null) {
            this.metricsRecorder = metricsRecorder;
        }
    }

    /** 是否已注入真实记录器（用于在热路径上避免为统计做多余探测）。 */
    protected boolean isMetricsRecording() {
        return metricsRecorder != NoOpCacheMetricsRecorder.INSTANCE;
    }

    /** 记录一次 L1 访问：hit=true 记 L1 命中，否则记未命中（NoOp 时无开销）。 */
    protected void recordL1Access(boolean hit) {
        if (hit) {
            metricsRecorder.recordHit(getCacheName(), "L1");
        } else {
            metricsRecorder.recordMiss(getCacheName());
        }
    }

    protected AbstractL1Cache(String cacheName, CacheConfig cacheConfig,
                              CacheLoader<Object, Object> cacheLoader, CacheSyncPolicy<?> cacheSyncPolicy) {
        super(cacheName, cacheConfig);
        this.cacheLoader = cacheLoader;
        this.cacheSyncPolicy = cacheSyncPolicy;
    }

    /**
     * 共用初始化：过期刷新调度 + NullValue 簿记缓存与清理任务。
     *
     * <p><b>必须由子类在构造函数末尾调用</b>——此时原生缓存字段已就绪，removalListener 内的
     * {@link #nativeInvalidate} 才安全（removalListener 只在淘汰时异步触发，远晚于构造）。
     */
    protected final void init(CacheConfig.AbstractL1Config l1Config) {
        if (!l1Config.isManualCache() && l1Config.isAutoRefreshExpireCache()) {
            ExpiredCacheRefreshSupport
                    .getInstance(l1Config.getRefreshThreadPoolSize())
                    .scheduleWithFixedDelay(
                            new ExpiredCacheRefreshTask(this),
                            5,
                            l1Config.getRefreshPeriod(),
                            TimeUnit.SECONDS
                    );
        }
        if (this.isAllowNullValues()) {
            this.nullValueCache = Caffeine.newBuilder()
                    // 复用共享守护线程池（见 NULL_VALUE_MAINTENANCE_EXECUTOR），避免每个缓存名各 new 一个永不 shutdown 的池
                    .executor(NULL_VALUE_MAINTENANCE_EXECUTOR)
                    .expireAfterWrite(getNullValueExpireTimeSeconds(), TimeUnit.SECONDS)
                    .maximumSize(cacheConfig.getNullValueMaxSize())
                    .removalListener((key, value, cause) -> {
                        log.debug("[NullValueCache] remove NullValue, removalCause={}, cacheName={}, key={}", cause, getCacheName(), key);
                        if (null != key) {
                            nativeInvalidate(key);
                            if (null != cacheSyncPolicy) {
                                cacheSyncPolicy.publish(createSyncMessage(key, CacheSyncTypeEnum.EVICT, "RemoveNullValue"));
                            }
                        }
                    })
                    .build();
            // manual 模式 cacheLoader 为 null，无需交给加载器
            if (null != cacheLoader) {
                cacheLoader.setNullValueCache(this.nullValueCache);
            }
            NullValueClearSupport.getInstance().scheduleWithFixedDelay(
                    new NullValueClearTask(getCacheName(), this.nullValueCache), 5,
                    cacheConfig.getNullValueClearPeriodSeconds(), TimeUnit.SECONDS);
            log.info("NullValueCache初始化成功, cacheName={}, expireTime={}s, maxSize={}, clearPeriodSeconds={}s",
                    getCacheName(), getNullValueExpireTimeSeconds(), cacheConfig.getNullValueMaxSize(), cacheConfig.getNullValueClearPeriodSeconds());
        }
    }

    @Override
    public CacheLoader<Object, Object> getCacheLoader() {
        return this.cacheLoader;
    }

    @Override
    public CacheSyncPolicy<?> getCacheSyncPolicy() {
        return this.cacheSyncPolicy;
    }

    @Override
    public void put(Object key, Object value) {
        if (!isAllowNullValues() && value == null) {
            nativeInvalidate(key);
            return;
        }
        nativePut(key, toCacheValue(value));
        if (log.isDebugEnabled()) {
            log.debug("put cache, cacheName={}, cacheSize={}, key={}", getCacheName(), nativeSize(), key);
        }
        // 允许 null 值且值为空，则记录到 nullValueCache，用于淘汰 NullValue
        if (isAllowNullValues() && (value == null || value instanceof NullValueWrapper)) {
            if (null != nullValueCache) {
                nullValueCache.put(key, 1);
            }
        }
        if (null != cacheSyncPolicy) {
            cacheSyncPolicy.publish(createSyncMessage(key, CacheSyncTypeEnum.REFRESH, "put"));
        }
    }

    @Override
    public void evict(Object key) {
        log.debug("evict cache, cacheName={}, key={}", getCacheName(), key);
        nativeInvalidate(key);
        if (null != nullValueCache) {
            nullValueCache.invalidate(key);
        }
        if (null != cacheSyncPolicy) {
            cacheSyncPolicy.publish(createSyncMessage(key, CacheSyncTypeEnum.EVICT, "evict"));
        }
    }

    @Override
    public void clear() {
        if (log.isDebugEnabled()) {
            log.debug("clear cache, cacheName={}, deleteCount={}", getCacheName(), nativeSize());
        }
        nativeInvalidateAll();
        if (null != nullValueCache) {
            nullValueCache.invalidateAll();
        }
        if (null != cacheSyncPolicy) {
            cacheSyncPolicy.publish(createSyncMessage(null, CacheSyncTypeEnum.CLEAR, "clear"));
        }
    }

    @Override
    public void invalidateCache(Object key) {
        if (null == key) {
            nativeInvalidateAll();
            if (null != nullValueCache) {
                nullValueCache.invalidateAll();
            }
        } else {
            nativeInvalidate(key);
            if (null != nullValueCache) {
                nullValueCache.invalidate(key);
            }
        }
    }

    @Override
    public boolean isExists(Object key) {
        boolean exist = nativeContainsKey(key);
        if (log.isDebugEnabled()) {
            log.debug("key is exists, cacheName={}, key={}, rslt={}", getCacheName(), key, exist);
        }
        return exist;
    }

    @Override
    public long size() {
        return nativeSize();
    }

    /**
     * 返回 L1 全部 key 的<b>只读</b>视图。
     *
     * <p>底层 {@link #nativeKeys()} 是缓存的 live 视图，直接外泄会让调用方 {@code remove()/clear()} 绕过
     * NullValue 簿记与跨节点同步广播、误删 L1 条目；故包一层 unmodifiable 防止外部改动回写底层缓存。
     */
    @Override
    public Set<Object> keys() {
        return Collections.unmodifiableSet(nativeKeys());
    }

    /**
     * 返回 L1 全部 value 的<b>只读</b>视图（理由同 {@link #keys()}）。
     */
    @Override
    public Collection<Object> values() {
        return Collections.unmodifiableCollection(nativeValues());
    }

    /**
     * 构造 L1 同步消息（跨节点广播用）。
     */
    protected CacheSyncMessage createSyncMessage(Object key, CacheSyncTypeEnum cacheSyncTypeEnum, String desc) {
        return new CacheSyncMessage()
                .setMsgSrc(getInstanceId())
                .setCacheType(getCacheType())
                .setCacheName(getCacheName())
                .setKey(key)
                .setCacheSyncType(cacheSyncTypeEnum)
                .setMsgDesc(desc);
    }

    // ---------- 原生缓存原子操作（由具体实现提供）----------

    protected abstract void nativePut(Object key, Object storeValue);

    protected abstract void nativeInvalidate(Object key);

    protected abstract void nativeInvalidateAll();

    protected abstract long nativeSize();

    protected abstract boolean nativeContainsKey(Object key);

    protected abstract Set<Object> nativeKeys();

    protected abstract Collection<Object> nativeValues();
}
