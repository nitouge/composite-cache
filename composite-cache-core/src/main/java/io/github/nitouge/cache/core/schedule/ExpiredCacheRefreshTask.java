package io.github.nitouge.cache.core.schedule;

import io.github.nitouge.cache.core.api.L1Cache;
import lombok.extern.slf4j.Slf4j;

/**
 * 刷新过期缓存任务
 * 
 * <p>该任务的主要目的是尽可能保证L1Cache中是最新的数据。
 * 如guava、caffeine在访问时，若数据过期则先返回旧数据，再执行数据加载。
 * 
 * <p>如果L1Cache是LoadingCache，并且自定义CacheLoader中L2Cache不为空，
 * 则同时刷新L1Cache和L2Cache。
 * 
 */
@Slf4j
public class ExpiredCacheRefreshTask implements Runnable {

    private final L1Cache l1Cache;

    public ExpiredCacheRefreshTask(L1Cache l1Cache) {
        if (l1Cache == null) {
            throw new IllegalArgumentException("L1Cache cannot be null");
        }
        this.l1Cache = l1Cache;
    }

    @Override
    public void run() {
        try {
            log.debug("Start refreshing expired cache for: {}", l1Cache.getCacheName());
            l1Cache.refreshAllExpiredCache();
            log.debug("Finished refreshing expired cache for: {}", l1Cache.getCacheName());
        } catch (Exception e) {
            log.error("Failed to refresh expired cache for: {}", l1Cache.getCacheName(), e);
        }
    }
}
