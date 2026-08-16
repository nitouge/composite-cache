package io.github.nitouge.cache.core.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 缓存统计聚合器
 *
 * <p>聚合所有缓存的统计信息，提供全局视图和定时上报功能。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>全局命中率统计</li>
 *   <li>分缓存命中率统计</li>
 *   <li>定时上报到监控系统</li>
 *   <li>历史趋势分析</li>
 * </ul>
 *
 */
@Slf4j
public class CacheStatisticsAggregator implements CacheMetricsRecorder {

    private final MeterRegistry registry;

    private final Map<String, CacheStats> cacheStatsMap = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    /**
     * 上报间隔（秒）
     */
    private final int reportIntervalSeconds;

    /**
     * 上报回调
     */
    private final StatisticsReporter reporter;

    public CacheStatisticsAggregator(MeterRegistry registry, int reportIntervalSeconds, StatisticsReporter reporter) {
        this.registry = registry;
        this.reportIntervalSeconds = reportIntervalSeconds;
        this.reporter = reporter;
        startScheduledReporting();
    }

    /**
     * 记录缓存命中
     */
    public void recordHit(String cacheName) {
        CacheStats stats = cacheStatsMap.computeIfAbsent(cacheName, k -> new CacheStats(cacheName));
        stats.incrementHit();
    }

    @Override
    public void recordHit(String cacheName, String level) {
        CacheStats stats = cacheStatsMap.computeIfAbsent(cacheName, k -> new CacheStats(cacheName));
        stats.incrementHit(level);
    }

    @Override
    public void recordEviction(String cacheName) {
        CacheStats stats = cacheStatsMap.computeIfAbsent(cacheName, k -> new CacheStats(cacheName));
        stats.incrementEviction();
    }

    @Override
    public void recordLatency(String cacheName, String operation, long duration, java.util.concurrent.TimeUnit unit) {
        // 聚合器聚焦命中率统计，耗时交由 Micrometer recorder 记录，这里不处理
    }

    /**
     * 记录缓存未命中
     *
     * @param cacheName
     */
    public void recordMiss(String cacheName) {
        CacheStats stats = cacheStatsMap.computeIfAbsent(cacheName, k -> new CacheStats(cacheName));
        stats.incrementMiss();
    }

    @Override
    public void recordHit(String cacheName, String level, int count) {
        if (count <= 0) {
            return;
        }
        cacheStatsMap.computeIfAbsent(cacheName, k -> new CacheStats(cacheName)).addHit(level, count);
    }

    @Override
    public void recordMiss(String cacheName, int count) {
        if (count <= 0) {
            return;
        }
        cacheStatsMap.computeIfAbsent(cacheName, k -> new CacheStats(cacheName)).addMiss(count);
    }

    @Override
    public void recordEviction(String cacheName, int count) {
        if (count <= 0) {
            return;
        }
        cacheStatsMap.computeIfAbsent(cacheName, k -> new CacheStats(cacheName)).addEviction(count);
    }

    /**
     * 获取指定缓存的统计信息
     *
     * @param cacheName
     * @return
     */
    public CacheStats getStats(String cacheName) {
        return cacheStatsMap.get(cacheName);
    }

    /**
     * 获取所有缓存的统计信息
     *
     * @return
     */
    public Map<String, CacheStats> getAllStats() {
        return new ConcurrentHashMap<>(cacheStatsMap);
    }

    /**
     * 获取全局统计信息
     *
     * @return
     */
    public GlobalCacheStats getGlobalStats() {
        long totalHits = 0;
        long totalMisses = 0;
        
        for (CacheStats stats : cacheStatsMap.values()) {
            totalHits += stats.getHitCount();
            totalMisses += stats.getMissCount();
        }
        
        return new GlobalCacheStats(totalHits, totalMisses);
    }

    /**
     * 启动定时上报
     */
    private void startScheduledReporting() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                reportStatistics();
            } catch (Exception e) {
                log.error("Failed to report cache statistics", e);
            }
        }, reportIntervalSeconds, reportIntervalSeconds, TimeUnit.SECONDS);
        
        log.info("Cache statistics reporting started, interval: {}s", reportIntervalSeconds);
    }

    /**
     * 上报统计信息
     */
    private void reportStatistics() {
        if (reporter == null) {
            return;
        }
        
        GlobalCacheStats globalStats = getGlobalStats();
        reporter.reportGlobal(globalStats);
        
        for (Map.Entry<String, CacheStats> entry : cacheStatsMap.entrySet()) {
            reporter.reportCache(entry.getKey(), entry.getValue());
        }
        
        if (log.isDebugEnabled()) {
            log.debug("Cache statistics reported: global hit rate = {}%", 
                String.format("%.2f", globalStats.getHitRate() * 100));
        }
    }

    /**
     * 关闭聚合器
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 缓存统计信息
     */
    @Data
    public static class CacheStats {
        private final String cacheName;
        private final AtomicLong hitCount = new AtomicLong(0);
        private final AtomicLong missCount = new AtomicLong(0);
        private final AtomicLong l1HitCount = new AtomicLong(0);
        private final AtomicLong l2HitCount = new AtomicLong(0);
        private final AtomicLong evictionCount = new AtomicLong(0);
        private long lastReportTime = System.currentTimeMillis();

        public CacheStats(String cacheName) {
            this.cacheName = cacheName;
        }

        public void incrementEviction() {
            evictionCount.incrementAndGet();
        }

        public long getEvictionCount() {
            return evictionCount.get();
        }

        public void incrementHit() {
            hitCount.incrementAndGet();
        }

        public void incrementHit(String level) {
            hitCount.incrementAndGet();
            if ("L1".equals(level)) {
                l1HitCount.incrementAndGet();
            } else if ("L2".equals(level)) {
                l2HitCount.incrementAndGet();
            }
        }

        public void incrementMiss() {
            missCount.incrementAndGet();
        }

        public void addHit(String level, long count) {
            hitCount.addAndGet(count);
            if ("L1".equals(level)) {
                l1HitCount.addAndGet(count);
            } else if ("L2".equals(level)) {
                l2HitCount.addAndGet(count);
            }
        }

        public void addMiss(long count) {
            missCount.addAndGet(count);
        }

        public void addEviction(long count) {
            evictionCount.addAndGet(count);
        }

        public long getHitCount() {
            return hitCount.get();
        }

        public long getMissCount() {
            return missCount.get();
        }

        public long getL1HitCount() {
            return l1HitCount.get();
        }

        public long getL2HitCount() {
            return l2HitCount.get();
        }

        public double getHitRate() {
            long total = hitCount.get() + missCount.get();
            return total > 0 ? (double) hitCount.get() / total : 0.0;
        }

        public long getTotalRequests() {
            return hitCount.get() + missCount.get();
        }
    }

    @Data
    public static class GlobalCacheStats {
        private final long totalHits;
        private final long totalMisses;

        public double getHitRate() {
            long total = totalHits + totalMisses;
            return total > 0 ? (double) totalHits / total : 0.0;
        }

        public long getTotalRequests() {
            return totalHits + totalMisses;
        }
    }

    /**
     * 统计上报接口
     */
    public interface StatisticsReporter {
        /**
         * 上报全局统计
         */
        void reportGlobal(GlobalCacheStats stats);

        /**
         * 上报单个缓存统计
         */
        void reportCache(String cacheName, CacheStats stats);
    }
}
