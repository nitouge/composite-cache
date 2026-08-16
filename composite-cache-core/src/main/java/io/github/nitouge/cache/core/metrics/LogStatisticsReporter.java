package io.github.nitouge.cache.core.metrics;

import lombok.extern.slf4j.Slf4j;

/**
 * 日志统计上报器
 * 
 */
@Slf4j
public class LogStatisticsReporter implements CacheStatisticsAggregator.StatisticsReporter {

    @Override
    public void reportGlobal(CacheStatisticsAggregator.GlobalCacheStats stats) {
        log.info("=== Global Cache Statistics ===");
        log.info("Total Requests: {}", stats.getTotalRequests());
        log.info("Total Hits: {}", stats.getTotalHits());
        log.info("Total Misses: {}", stats.getTotalMisses());
        log.info("Hit Rate: {}%", String.format("%.2f", stats.getHitRate() * 100));
    }

    @Override
    public void reportCache(String cacheName, CacheStatisticsAggregator.CacheStats stats) {
        log.info("Cache [{}] - Requests: {}, Hits: {} (L1: {}, L2: {}), Misses: {}, Hit Rate: {}%",
            cacheName,
            stats.getTotalRequests(),
            stats.getHitCount(),
            stats.getL1HitCount(),
            stats.getL2HitCount(),
            stats.getMissCount(),
            String.format("%.2f", stats.getHitRate() * 100));
    }
}
