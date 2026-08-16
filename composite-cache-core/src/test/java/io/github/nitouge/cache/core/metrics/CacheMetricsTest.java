package io.github.nitouge.cache.core.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CacheMetrics} 的命中率与清理回归测试（C10-1 / C10-2）。
 *
 * <p>历史 bug：命中计数器按 {@code "cacheName:level"} 分桶，但 {@code getHitRate} 用
 * {@code hitCounters.get(cacheName)}（无 level 后缀）→ 命中数永远取不到、命中率恒为 0；
 * {@code clear} 同样按 {@code cacheName} 删不掉 {@code cacheName:level} 的桶，且不从 registry 注销 meter。
 *
 */
public class CacheMetricsTest {

    @Test
    public void getHitRate_shouldSumAllLevels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheMetrics metrics = new CacheMetrics(registry);

        metrics.recordHit("user", "L1");
        metrics.recordHit("user", "L1");
        metrics.recordHit("user", "L2");
        metrics.recordMiss("user");

        // hits = 3(L1) + ... 取 L1*2 + L2*1 = 3；miss = 1 → 命中率 3/4 = 0.75
        assertThat(metrics.getHitRate("user")).isEqualTo(0.75);
    }

    @Test
    public void getHitRate_noData_returnsMinusOne() {
        CacheMetrics metrics = new CacheMetrics(new SimpleMeterRegistry());
        assertThat(metrics.getHitRate("absent")).isEqualTo(-1);
    }

    @Test
    public void clear_shouldRemoveCountersAndDeregisterMeters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheMetrics metrics = new CacheMetrics(registry);

        metrics.recordHit("user", "L1");
        metrics.recordMiss("user");
        metrics.recordEviction("user");
        metrics.recordLatency("user", "get", 5, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(registry.find("cache.hit").counter()).isNotNull();

        metrics.clear("user");

        // 清理后命中率回到"无数据"，且 registry 中相关 meter 已注销
        assertThat(metrics.getHitRate("user")).isEqualTo(-1);
        assertThat(registry.find("cache.hit").counter()).isNull();
        assertThat(registry.find("cache.miss").counter()).isNull();
        assertThat(registry.find("cache.eviction").counter()).isNull();
        assertThat(registry.find("cache.latency").timer()).isNull();
    }
}
