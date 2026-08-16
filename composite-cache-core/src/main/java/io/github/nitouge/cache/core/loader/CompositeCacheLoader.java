package io.github.nitouge.cache.core.loader;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.nitouge.cache.core.api.CacheLoader;
import io.github.nitouge.cache.core.api.CacheSyncPolicy;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.loader.task.LoadValueTask;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;

/**
 * 组合缓存加载器
 *
 * <p>主要目的是为了使用 refreshAfterWrite 策略的特性：
 * <ul>
 *   <li>仅加载数据的线程阻塞</li>
 *   <li>其他线程返回旧值（提高并发性能）</li>
 * </ul>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>管理 ValueLoaderTask，为不同 key 维护对应的数据加载器</li>
 *   <li>使用 Caffeine 缓存管理 ValueLoaderTask，防止内存溢出</li>
 *   <li>支持从 L2 缓存加载数据</li>
 *   <li>支持缓存同步策略</li>
 * </ul>
 *
 */
@Slf4j
public class CompositeCacheLoader implements CacheLoader<Object, Object> {

    /**
     * ValueLoaderTask 缓存（key -> ValueLoaderTask）
     *
     * <p>用于保证并发场景下不同 key 能找到对应的数据加载器
     *
     * <p>使用 Caffeine 缓存的优势：
     * <ul>
     *   <li>基于大小的自动淘汰机制，防止内存溢出</li>
     *   <li>异步清理任务，不阻塞主线程</li>
     *   <li>支持移除监听器，方便调试</li>
     * </ul>
     *
     * <p>注意：极端情况下（大量不同 key 访问），清理任务可能堆积，
     * 导致缓存未及时清理。建议监控 valueLoaderTaskCache 的大小
     */
    private final Cache<Object, LoadValueTask> valueLoaderTaskCache;

    private final String instanceId;

    private final String cacheType;

    private final String cacheName;

    private L2Cache l2Cache;

    private CacheSyncPolicy<?> cacheSyncPolicy;

    private boolean allowNullValues;

    private Cache<Object, Integer> nullValueCache;

    private static final long DEFAULT_MAX_SIZE = 5000L;
    
    private CompositeCacheLoader(String instanceId, String cacheType, String cacheName, Long maxSize) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("cacheName cannot be null or empty");
        }
        
        this.instanceId = instanceId;
        this.cacheType = cacheType;
        this.cacheName = cacheName;
        
        long actualMaxSize = (maxSize != null && maxSize > 0) ? maxSize : DEFAULT_MAX_SIZE;
        
        valueLoaderTaskCache = Caffeine.newBuilder()
                .maximumSize(actualMaxSize)
                .removalListener((key, value, cause) -> {
                    log.debug("ValueLoader recycled: cacheName={}, cause={}, key={}", 
                            cacheName, cause, key);
                })
                .build();
                
        log.info("CompositeCacheLoader initialized: cacheName={}, maxSize={}", cacheName, actualMaxSize);
    }

    /**
     * create CacheLoader instance
     */
    public static CompositeCacheLoader newInstance(String instanceId, String cacheType, String cacheName, Long maxSize) {
        return new CompositeCacheLoader(instanceId, cacheType, cacheName, maxSize);
    }

    @Override
    public void setL2Cache(L2Cache l2Cache) {
        this.l2Cache = l2Cache;
    }

    @Override
    public void setCacheSyncPolicy(CacheSyncPolicy<?> cacheSyncPolicy) {
        this.cacheSyncPolicy = cacheSyncPolicy;
    }

    @Override
    public void setAllowNullValues(boolean allowNullValues) {
        this.allowNullValues = allowNullValues;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void setNullValueCache(Object nullValueCache) {
        if (nullValueCache instanceof Cache) {
            this.nullValueCache = (Cache<Object, Integer>) nullValueCache;
        }
    }

    /**
     * 登记/补全某 key 的回源任务
     *
     * <p>已存在同 key 任务时不覆盖整个任务，仅在原任务<b>缺少</b> valueLoader 且本次传入非空时补上 callable，
     * 以保留任务上已有状态、避免重复构造
     */
    @Override
    public void addValueLoaderTask(Object key, Callable<?> callable) {
        if (key == null) {
            log.warn("Key is null, skip adding ValueLoaderTask");
            return;
        }
        
        LoadValueTask existingTask = valueLoaderTaskCache.getIfPresent(key);
        if (existingTask == null) {
            // 创建新的 ValueLoaderTask
            LoadValueTask newTask = new LoadValueTask(cacheName, key, callable);
            valueLoaderTaskCache.put(key, newTask);
            log.debug("Added new ValueLoaderTask: cacheName={}, key={}", cacheName, key);
        } else {
            // 更新现有的 ValueLoaderTask
            if (existingTask.getValueLoader() == null && callable != null) {
                existingTask.setValueLoader(callable);
                log.debug("Updated ValueLoaderTask with new callable: cacheName={}, key={}", cacheName, key);
            }
        }
    }

    @Override
    public void removeValueLoaderTask(Object key) {
        valueLoaderTaskCache.invalidate(key);
    }

    /**
     * 由 Caffeine 在 refreshAfterWrite 触发时回调
     *
     * <p>每次调用都新建一个 {@link ValueLoadFunction}，将回源(valueLoader)、L2 读写、NullValue 防穿透与缓存同步串起来执行；
     * 此处取出的 valueLoader 可能为 {@code null}（尚未登记），具体兜底由 {@link ValueLoadFunction} 处理
     */
    @Override
    public Object load(Object key) {
        if (key == null) {
            log.warn("Key is null, return null");
            return null;
        }
        
        try {
            LoadValueTask valueLoader = valueLoaderTaskCache.getIfPresent(key);
            ValueLoadFunction valueLoadFunction = new ValueLoadFunction(
                    instanceId, cacheType, cacheName,
                    l2Cache, cacheSyncPolicy, valueLoader,
                    allowNullValues, nullValueCache
            );
            
            return valueLoadFunction.apply(key);
        } catch (Exception e) {
            log.error("Failed to load cache data: cacheName={}, key={}", cacheName, key, e);
            throw e;
        }
    }

    @Override
    public LoadValueTask getValueLoaderTask(Object key) {
        return valueLoaderTaskCache.getIfPresent(key);
    }

}
