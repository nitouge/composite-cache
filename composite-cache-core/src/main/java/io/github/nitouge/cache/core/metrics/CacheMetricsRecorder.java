package io.github.nitouge.cache.core.metrics;

import java.util.concurrent.TimeUnit;

/**
 * 缓存指标记录器（统一口径）。
 *
 * <p>本接口是缓存监控的<b>唯一记录入口</b>，用于消除历史上 {@code CacheMetrics}（Micrometer）、
 * {@code CacheStatisticsAggregator}（内存聚合+上报）、{@code AnnotationMetrics}（注解维度）三套
 * 并存、互相重复计数的问题。
 *
 * <h3>实现</h3>
 * <ul>
 *   <li>{@link CacheMetrics} —— 基于 Micrometer，对接 Prometheus 等监控系统</li>
 *   <li>{@link CacheStatisticsAggregator} —— 内存聚合 + 定时上报（日志/HTTP）</li>
 *   <li>{@link CompositeCacheMetricsRecorder} —— 扇出到多个 recorder</li>
 *   <li>{@link NoOpCacheMetricsRecorder} —— 关闭监控时的空实现</li>
 * </ul>
 *
 * <h3>记录点</h3>
 * <p>统一在 {@code CompositeCache} 的读写路径记录，注解切面与编程式 API 都经由它，避免重复计数。
 *
 */
public interface CacheMetricsRecorder {

    /**
     * 记录命中（不区分级别）
     */
    default void recordHit(String cacheName) {
        recordHit(cacheName, null);
    }

    /**
     * 记录命中（区分级别 L1/L2，null 表示不区分）
     */
    void recordHit(String cacheName, String level);

    /**
     * 记录未命中
     */
    void recordMiss(String cacheName);

    /**
     * 记录驱逐
     */
    void recordEviction(String cacheName);

    /**
     * 批量记录命中（按数量一次累加）。默认逐次调用 {@link #recordHit(String, String)}，
     * 实现可重写为高效批量累加（如计数器一次性加 {@code count}）。
     */
    default void recordHit(String cacheName, String level, int count) {
        for (int i = 0; i < count; i++) {
            recordHit(cacheName, level);
        }
    }

    /**
     * 批量记录未命中（按数量一次累加）。默认逐次调用 {@link #recordMiss(String)}。
     */
    default void recordMiss(String cacheName, int count) {
        for (int i = 0; i < count; i++) {
            recordMiss(cacheName);
        }
    }

    /**
     * 批量记录驱逐（按数量一次累加）。默认逐次调用 {@link #recordEviction(String)}。
     */
    default void recordEviction(String cacheName, int count) {
        for (int i = 0; i < count; i++) {
            recordEviction(cacheName);
        }
    }

    /**
     * 记录某类操作的耗时（get/put/evict 等）
     */
    void recordLatency(String cacheName, String operation, long duration, TimeUnit unit);

    /**
     * 记录一次回源加载（L2 未命中回源 DB）的耗时与成败。默认空实现。
     */
    default void recordLoad(String cacheName, long duration, TimeUnit unit, boolean success) {
        // optional
    }

    /**
     * 记录一次操作异常。默认空实现。
     */
    default void recordException(String cacheName, String operation) {
        // optional
    }
}
