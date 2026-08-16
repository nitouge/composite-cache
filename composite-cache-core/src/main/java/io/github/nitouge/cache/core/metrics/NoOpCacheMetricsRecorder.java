package io.github.nitouge.cache.core.metrics;

import java.util.concurrent.TimeUnit;

/**
 * 空实现的指标记录器（关闭监控时使用），单例。
 *
 */
public final class NoOpCacheMetricsRecorder implements CacheMetricsRecorder {

    public static final NoOpCacheMetricsRecorder INSTANCE = new NoOpCacheMetricsRecorder();

    private NoOpCacheMetricsRecorder() {
    }

    @Override
    public void recordHit(String cacheName, String level) {
    }

    @Override
    public void recordMiss(String cacheName) {
    }

    @Override
    public void recordEviction(String cacheName) {
    }

    @Override
    public void recordHit(String cacheName, String level, int count) {
    }

    @Override
    public void recordMiss(String cacheName, int count) {
    }

    @Override
    public void recordEviction(String cacheName, int count) {
    }

    @Override
    public void recordLatency(String cacheName, String operation, long duration, TimeUnit unit) {
    }
}
