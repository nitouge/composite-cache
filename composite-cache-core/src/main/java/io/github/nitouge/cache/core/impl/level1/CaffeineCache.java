package io.github.nitouge.cache.core.impl.level1;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.LoadingCache;
import io.github.nitouge.cache.core.api.CacheLoader;
import io.github.nitouge.cache.core.api.CacheSyncPolicy;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.consts.enums.CacheTypeEnum;
import io.github.nitouge.cache.core.impl.base.AbstractL1Cache;
import io.github.nitouge.cache.core.loader.ValueLoadFunction;
import io.github.nitouge.cache.core.loader.task.LoadValueTask;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Caffeine Cache（L1 本地缓存实现）。
 *
 * <p>共用逻辑（NullValue 簿记、过期刷新调度、写路径 put/evict/clear、同步消息等）见父类 {@link AbstractL1Cache}；
 * 本类只实现 Caffeine 原生差异：读路径、刷新与一组原子操作。
 *
 */
@SuppressWarnings("unchecked")
@Slf4j
public class CaffeineCache extends AbstractL1Cache {

    /**
     * L1 Caffeine Cache
     */
    private final Cache<Object, Object> caffeineCache;

    public CaffeineCache(String cacheName, CacheConfig cacheConfig, CacheLoader<Object, Object> cacheLoader,
                         CacheSyncPolicy<?> cacheSyncPolicy, Cache<Object, Object> caffeineCache) {
        super(cacheName, cacheConfig, cacheLoader, cacheSyncPolicy);
        this.caffeineCache = caffeineCache;
        // 原生缓存字段已就绪后再做共用初始化（NullValue 簿记 + 刷新调度）
        init(cacheConfig.getCaffeine());
    }

    @Override
    public String getCacheType() {
        return CacheTypeEnum.CAFFEINE.name();
    }

    @Override
    public Cache<Object, Object> getActualCache() {
        return this.caffeineCache;
    }

    @Override
    public boolean isLoadingCache() {
        return this.caffeineCache instanceof LoadingCache && null != getCacheLoader();
    }

    @SuppressWarnings("unchecked")
    @Override
    public Object get(Object key) {
        if (isLoadingCache()) {
            // refreshAfterWrite 策略下只阻塞加载线程，其它线程返回旧值（异步加载则全部返回旧值）
            Object value = ((LoadingCache) this.caffeineCache).get(key);
            if (log.isDebugEnabled()) {
                log.debug("LoadingCache.get cache, cacheName={}, key={}, hit={}", getCacheName(), key, value != null);
            }
            return fromCacheValue(value);
        }
        return fromCacheValue(this.caffeineCache.getIfPresent(key));
    }

    @Override
    public Object getIfPresent(Object key) {
        Object raw = this.caffeineCache.getIfPresent(key);
        recordL1Access(raw != null); // L1-only 部署的命中/未命中统计（NoOp 时无开销）
        return fromCacheValue(raw);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Object key, Callable<T> callable) {
        if (isLoadingCache()) {
            // 仅在 L1-only 注入了真实记录器时才预探命中（避免组合层下的多余探测）
            boolean hit = isMetricsRecording() && this.caffeineCache.getIfPresent(key) != null;
            // 将 Callable 设置到自定义 CacheLoader，以便 load() 中执行业务方法加载数据
            getCacheLoader().addValueLoaderTask(key, callable);
            Object value = ((LoadingCache) this.caffeineCache).get(key);
            if (isMetricsRecording()) {
                recordL1Access(hit);
            }
            if (log.isDebugEnabled()) {
                log.debug("LoadingCache.get(key, callable) cache, cacheName={}, key={}, hit={}", getCacheName(), key, value != null);
            }
            return (T) fromCacheValue(value);
        }
        boolean hit = isMetricsRecording() && this.caffeineCache.getIfPresent(key) != null;
        // 同步加载：仅一个线程加载，其它线程阻塞（Caffeine get(key, Function) 保证单飞）
        Object value = this.caffeineCache.get(key, new ValueLoadFunction(getInstanceId(), getCacheType(), getCacheName(),
                null, getCacheSyncPolicy(), new LoadValueTask(getCacheName(), key, callable),
                isAllowNullValues(), nullValueCache));
        if (isMetricsRecording()) {
            recordL1Access(hit);
        }
        if (log.isDebugEnabled()) {
            log.debug("Cache.get(key, callable) cache, cacheName={}, key={}, hit={}", getCacheName(), key, value != null);
        }
        return (T) fromCacheValue(value);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void refresh(Object key) {
        if (isLoadingCache()) {
            // 确保该 key 已注册 ValueLoaderTask（refresh 触发的 load() 据此回源）。
            // 同一 key 的刷新单飞由 Caffeine LoadingCache 自身保证（在途刷新不会重复触发）。
            if (getCacheLoader().getValueLoaderTask(key) == null) {
                getCacheLoader().addValueLoaderTask(key, null);
            }
            ((LoadingCache) caffeineCache).refresh(key);
        }
    }

    @Override
    public void refreshAll() {
        if (isLoadingCache()) {
            LoadingCache loadingCache = (LoadingCache) caffeineCache;
            for (Object key : loadingCache.asMap().keySet()) {
                if (log.isDebugEnabled()) {
                    log.debug("refreshAll cache, cacheName={}, key={}", getCacheName(), key);
                }
                this.refresh(key);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void refreshExpiredCache(Object key) {
        if (isLoadingCache()) {
            if (log.isDebugEnabled()) {
                log.debug("refreshExpireCache, cacheName={}, key={}", getCacheName(), key);
            }
            // 通过 LoadingCache.get(key) 刷新过期缓存
            ((LoadingCache) caffeineCache).get(key);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void refreshAllExpiredCache() {
        if (isLoadingCache()) {
            LoadingCache loadingCache = (LoadingCache) caffeineCache;
            if (null != nullValueCache) {
                log.debug("refreshAllExpireCache, cacheName={}, size={}, NullValueSize={}, stats={}", getCacheName(), loadingCache.estimatedSize(), nullValueCache.estimatedSize(), loadingCache.stats());
            } else {
                log.debug("refreshAllExpireCache, cacheName={}, size={}, stats={}", getCacheName(), loadingCache.estimatedSize(), loadingCache.stats());
            }
            for (Object key : loadingCache.asMap().keySet()) {
                if (log.isDebugEnabled()) {
                    log.debug("refreshAllExpireCache, cacheName={}, key={}", getCacheName(), key);
                }
                loadingCache.get(key);// 通过 LoadingCache.get(key) 刷新过期缓存
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K, V> Map<K, V> batchGet(Map<K, Object> keyMap, boolean returnNullValueKey) {
        // 命中列表
        Map<K, V> hitMap = new HashMap<>();
        keyMap.forEach((key, cacheKey) -> {
            // 仅获取，不触发 load
            Object value = this.caffeineCache.getIfPresent(cacheKey);
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
        caffeineCache.put(key, storeValue);
    }

    @Override
    protected void nativeInvalidate(Object key) {
        caffeineCache.invalidate(key);
    }

    @Override
    protected void nativeInvalidateAll() {
        caffeineCache.invalidateAll();
    }

    @Override
    protected long nativeSize() {
        return caffeineCache.asMap().size();
    }

    @Override
    protected boolean nativeContainsKey(Object key) {
        return caffeineCache.asMap().containsKey(key);
    }

    @Override
    protected Set<Object> nativeKeys() {
        return caffeineCache.asMap().keySet();
    }

    @Override
    protected Collection<Object> nativeValues() {
        return caffeineCache.asMap().values();
    }
}
