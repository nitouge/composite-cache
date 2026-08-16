package io.github.nitouge.cache.core.metrics;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 组合指标记录器：把每次记录扇出到多个 {@link CacheMetricsRecorder}。
 *
 * <p>典型用法：同时对接 Micrometer（{@link CacheMetrics}）与内存聚合上报
 * （{@link CacheStatisticsAggregator}），一次记录两边都生效，避免业务代码重复调用。
 *
 */
@Slf4j
public class CompositeCacheMetricsRecorder implements CacheMetricsRecorder {

    private final List<CacheMetricsRecorder> delegates;

    public CompositeCacheMetricsRecorder(List<CacheMetricsRecorder> delegates) {
        this.delegates = new ArrayList<>();
        if (delegates != null) {
            for (CacheMetricsRecorder d : delegates) {
                if (d != null && d != this) {
                    this.delegates.add(d);
                }
            }
        }
    }

    public CompositeCacheMetricsRecorder(CacheMetricsRecorder... delegates) {
        this(delegates == null ? null : Arrays.asList(delegates));
    }

    public boolean isEmpty() {
        return delegates.isEmpty();
    }

    @Override
    public void recordHit(String cacheName, String level) {
        for (CacheMetricsRecorder d : delegates) {
            safe(() -> d.recordHit(cacheName, level));
        }
    }

    @Override
    public void recordMiss(String cacheName) {
        for (CacheMetricsRecorder d : delegates) {
            safe(() -> d.recordMiss(cacheName));
        }
    }

    @Override
    public void recordEviction(String cacheName) {
        for (CacheMetricsRecorder d : delegates) {
            safe(() -> d.recordEviction(cacheName));
        }
    }

    @Override
    public void recordHit(String cacheName, String level, int count) {
        for (CacheMetricsRecorder d : delegates) {
            safe(() -> d.recordHit(cacheName, level, count));
        }
    }

    @Override
    public void recordMiss(String cacheName, int count) {
        for (CacheMetricsRecorder d : delegates) {
            safe(() -> d.recordMiss(cacheName, count));
        }
    }

    @Override
    public void recordEviction(String cacheName, int count) {
        for (CacheMetricsRecorder d : delegates) {
            safe(() -> d.recordEviction(cacheName, count));
        }
    }

    @Override
    public void recordLatency(String cacheName, String operation, long duration, TimeUnit unit) {
        for (CacheMetricsRecorder d : delegates) {
            safe(() -> d.recordLatency(cacheName, operation, duration, unit));
        }
    }

    @Override
    public void recordLoad(String cacheName, long duration, TimeUnit unit, boolean success) {
        for (CacheMetricsRecorder d : delegates) {
            safe(() -> d.recordLoad(cacheName, duration, unit, success));
        }
    }

    @Override
    public void recordException(String cacheName, String operation) {
        for (CacheMetricsRecorder d : delegates) {
            safe(() -> d.recordException(cacheName, operation));
        }
    }

    /**
     * 监控记录失败不应影响业务流程，吞掉异常仅记日志。
     */
    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.debug("metrics recorder delegate failed", e);
        }
    }
}
