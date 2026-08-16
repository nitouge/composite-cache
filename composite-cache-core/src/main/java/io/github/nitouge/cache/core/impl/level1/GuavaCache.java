package io.github.nitouge.cache.core.impl.level1;

import com.google.common.cache.CacheLoader.InvalidCacheLoadException;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import io.github.nitouge.cache.core.api.CacheLoader;
import io.github.nitouge.cache.core.api.CacheSyncPolicy;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.consts.enums.CacheTypeEnum;
import io.github.nitouge.cache.core.exception.CacheException;
import io.github.nitouge.cache.core.impl.base.AbstractL1Cache;
import io.github.nitouge.cache.core.loader.ValueLoadFunction;
import io.github.nitouge.cache.core.loader.task.LoadValueTask;
import io.github.nitouge.cache.core.wrapper.NullValueWrapper;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

/**
 * Guava Cache（L1 本地缓存实现，Caffeine 的可选替代）。
 *
 * <p>共用逻辑（NullValue 簿记、过期刷新调度、写路径 put/evict/clear、同步消息等）见父类 {@link AbstractL1Cache}；
 * 本类只实现 Guava 原生差异。
 *
 * <h3>与 Caffeine 的关键差异</h3>
 * <ul>
 *   <li>Guava 缓存<b>不允许 null 值</b>。框架通过 {@link NullValueWrapper} 将 null 包装为占位对象后存储；
 *       当 {@code allowNullValues=false} 且数据缺失时，LoadingCache 的 load 返回 null 会抛
 *       {@link InvalidCacheLoadException}，此处统一捕获并按"未命中"处理（返回 null）。</li>
 *   <li>内部记录 NullValue TTL 的 {@code nullValueCache} 复用 Caffeine 实现（见父类），
 *       以保证 NullValue 语义在不同 L1 实现间完全一致，并直接复用现有加载器集成。</li>
 * </ul>
 *
 * @see CaffeineCache 对等的 Caffeine 实现
 */
@Slf4j
public class GuavaCache extends AbstractL1Cache {

    /**
     * L1 Guava Cache
     */
    private final com.google.common.cache.Cache<Object, Object> guavaCache;

    public GuavaCache(String cacheName, CacheConfig cacheConfig, CacheLoader<Object, Object> cacheLoader,
                      CacheSyncPolicy<?> cacheSyncPolicy, com.google.common.cache.Cache<Object, Object> guavaCache) {
        super(cacheName, cacheConfig, cacheLoader, cacheSyncPolicy);
        this.guavaCache = guavaCache;
        // 原生缓存字段已就绪后再做共用初始化（NullValue 簿记 + 刷新调度）
        init(cacheConfig.getGuava());
    }

    @Override
    public String getCacheType() {
        return CacheTypeEnum.GUAVA.name();
    }

    @Override
    public com.google.common.cache.Cache<Object, Object> getActualCache() {
        return this.guavaCache;
    }

    @Override
    public boolean isLoadingCache() {
        return this.guavaCache instanceof LoadingCache && null != getCacheLoader();
    }

    @Override
    public Object get(Object key) {
        if (isLoadingCache()) {
            // LoadingCache 在数据过期时（若配置了 refresh）会先返回旧值，再异步加载
            Object value = loadingGet(key);
            if (log.isDebugEnabled()) {
                log.debug("LoadingCache.get cache, cacheName={}, key={}, hit={}", getCacheName(), key, value != null);
            }
            return fromCacheValue(value);
        }
        return fromCacheValue(this.guavaCache.getIfPresent(key));
    }

    @Override
    public Object getIfPresent(Object key) {
        Object raw = this.guavaCache.getIfPresent(key);
        recordL1Access(raw != null); // L1-only 部署的命中/未命中统计（NoOp 时无开销）
        return fromCacheValue(raw);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Object key, Callable<T> callable) {
        if (isLoadingCache()) {
            boolean hit = isMetricsRecording() && this.guavaCache.getIfPresent(key) != null;
            // 将 Callable 设置到自定义 CacheLoader，以便 load() 中执行业务方法加载数据
            getCacheLoader().addValueLoaderTask(key, callable);
            Object value = loadingGet(key);
            if (isMetricsRecording()) {
                recordL1Access(hit);
            }
            if (log.isDebugEnabled()) {
                log.debug("LoadingCache.get(key, callable) cache, cacheName={}, key={}, hit={}", getCacheName(), key, value != null);
            }
            return (T) fromCacheValue(value);
        }
        boolean hit = isMetricsRecording() && this.guavaCache.getIfPresent(key) != null;
        // 同步加载：仅一个线程加载，其它线程阻塞（Guava 的 get(key, Callable) 保证单飞）
        ValueLoadFunction valueLoadFunction = new ValueLoadFunction(getInstanceId(), getCacheType(), getCacheName(),
                null, getCacheSyncPolicy(), new LoadValueTask(getCacheName(), key, callable),
                isAllowNullValues(), nullValueCache);
        try {
            Object value = this.guavaCache.get(key, () -> valueLoadFunction.apply(key));
            if (isMetricsRecording()) {
                recordL1Access(hit);
            }
            if (log.isDebugEnabled()) {
                log.debug("Cache.get(key, callable) cache, cacheName={}, key={}, hit={}", getCacheName(), key, value != null);
            }
            return (T) fromCacheValue(value);
        } catch (InvalidCacheLoadException e) {
            // 加载结果为 null（不缓存），按未命中处理
            return null;
        } catch (ExecutionException | UncheckedExecutionException e) {
            throw unwrapLoadException(key, e);
        }
    }

    /**
     * 通过 LoadingCache 加载数据，统一处理 Guava 不允许 null 值带来的异常。
     */
    @SuppressWarnings("unchecked")
    private Object loadingGet(Object key) {
        try {
            return ((LoadingCache<Object, Object>) this.guavaCache).get(key);
        } catch (InvalidCacheLoadException e) {
            // CacheLoader 返回 null（allowNullValues=false 且数据缺失），按未命中处理
            return null;
        } catch (ExecutionException | UncheckedExecutionException e) {
            throw unwrapLoadException(key, e);
        }
    }

    /**
     * 将 Guava 包装的加载异常解包为框架运行时异常。
     */
    private RuntimeException unwrapLoadException(Object key, Exception e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        return new CacheException(getCacheName(), String.valueOf(key), "Guava cache load failed", cause != null ? cause : e);
    }

    @Override
    public void refresh(Object key) {
        if (isLoadingCache()) {
            // 确保该 key 已注册 ValueLoaderTask（refresh 触发的 load() 据此回源）。
            // 同一 key 的刷新单飞由 Guava LoadingCache 自身保证（在途加载不会重复触发）。
            if (getCacheLoader().getValueLoaderTask(key) == null) {
                getCacheLoader().addValueLoaderTask(key, null);
            }
            ((LoadingCache<Object, Object>) guavaCache).refresh(key);
        }
    }

    @Override
    public void refreshAll() {
        if (isLoadingCache()) {
            for (Object key : guavaCache.asMap().keySet()) {
                if (log.isDebugEnabled()) {
                    log.debug("refreshAll cache, cacheName={}, key={}", getCacheName(), key);
                }
                this.refresh(key);
            }
        }
    }

    @Override
    public void refreshExpiredCache(Object key) {
        if (isLoadingCache()) {
            if (log.isDebugEnabled()) {
                log.debug("refreshExpireCache, cacheName={}, key={}", getCacheName(), key);
            }
            // 通过 LoadingCache.get(key) 刷新过期缓存
            loadingGet(key);
        }
    }

    @Override
    public void refreshAllExpiredCache() {
        if (isLoadingCache()) {
            if (null != nullValueCache) {
                log.debug("refreshAllExpireCache, cacheName={}, size={}, NullValueSize={}, stats={}", getCacheName(), guavaCache.size(), nullValueCache.estimatedSize(), guavaCache.stats());
            } else {
                log.debug("refreshAllExpireCache, cacheName={}, size={}, stats={}", getCacheName(), guavaCache.size(), guavaCache.stats());
            }
            for (Object key : guavaCache.asMap().keySet()) {
                if (log.isDebugEnabled()) {
                    log.debug("refreshAllExpireCache, cacheName={}, key={}", getCacheName(), key);
                }
                loadingGet(key);// 通过 LoadingCache.get(key) 刷新过期缓存
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K, V> Map<K, V> batchGet(Map<K, Object> keyMap, boolean returnNullValueKey) {
        // 命中列表
        Map<K, V> hitMap = new HashMap<>();
        keyMap.forEach((key, cacheKey) -> {
            // 仅获取，不触发 load（与 CaffeineCache.batchGet 语义一致）
            Object value = this.guavaCache.getIfPresent(cacheKey);
            if (log.isDebugEnabled()) {
                log.debug("batchGet cache, cacheName={}, cacheKey={}, hit={}", getCacheName(), cacheKey, value != null);
            }
            // value=null 表示 key 不存在，不放入返回数据
            if (value == null) {
                return;
            }
            V warpValue = (V) fromCacheValue(value);
            if (warpValue != null) {
                hitMap.put(key, warpValue);
                return;
            }
            // value=NullValue 且 returnNullValueKey=true，放入返回数据（供上层过滤、防止穿透到下一层）
            if (returnNullValueKey) {
                hitMap.put(key, null);
            }
        });
        return hitMap;
    }

    // ---------- 原生缓存原子操作 ----------

    @Override
    protected void nativePut(Object key, Object storeValue) {
        guavaCache.put(key, storeValue);
    }

    @Override
    protected void nativeInvalidate(Object key) {
        guavaCache.invalidate(key);
    }

    @Override
    protected void nativeInvalidateAll() {
        guavaCache.invalidateAll();
    }

    @Override
    protected long nativeSize() {
        return guavaCache.size();
    }

    @Override
    protected boolean nativeContainsKey(Object key) {
        return guavaCache.asMap().containsKey(key);
    }

    @Override
    protected Set<Object> nativeKeys() {
        return guavaCache.asMap().keySet();
    }

    @Override
    protected Collection<Object> nativeValues() {
        return guavaCache.asMap().values();
    }
}
