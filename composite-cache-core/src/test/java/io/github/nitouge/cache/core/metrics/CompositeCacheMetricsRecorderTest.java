package io.github.nitouge.cache.core.metrics;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link CompositeCacheMetricsRecorder} 扇出和弹性的测试。
 *
 */
public class CompositeCacheMetricsRecorderTest {

    @Test
    public void fansOutToAllDelegates() {
        CacheMetricsRecorder a = mock(CacheMetricsRecorder.class);
        CacheMetricsRecorder b = mock(CacheMetricsRecorder.class);
        CompositeCacheMetricsRecorder composite = new CompositeCacheMetricsRecorder(Arrays.asList(a, b));

        composite.recordHit("c", "L1");
        composite.recordMiss("c");
        composite.recordEviction("c");
        composite.recordLatency("c", "get", 10, TimeUnit.MILLISECONDS);

        verify(a, times(1)).recordHit("c", "L1");
        verify(b, times(1)).recordHit("c", "L1");
        verify(a, times(1)).recordMiss("c");
        verify(b, times(1)).recordMiss("c");
        verify(a, times(1)).recordEviction("c");
        verify(a, times(1)).recordLatency("c", "get", 10, TimeUnit.MILLISECONDS);
    }

    @Test
    public void oneDelegateThrowing_doesNotBreakOthers() {
        CacheMetricsRecorder bad = mock(CacheMetricsRecorder.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(bad).recordMiss("c");
        CacheMetricsRecorder good = mock(CacheMetricsRecorder.class);
        CompositeCacheMetricsRecorder composite = new CompositeCacheMetricsRecorder(Arrays.asList(bad, good));

        // 不应抛出，且 good 仍被调用
        composite.recordMiss("c");

        verify(good, times(1)).recordMiss("c");
    }

    @Test
    public void filtersOutNullDelegates() {
        CacheMetricsRecorder a = mock(CacheMetricsRecorder.class);
        CompositeCacheMetricsRecorder composite = new CompositeCacheMetricsRecorder(a, null);
        assertThat(composite.isEmpty()).isFalse();
        composite.recordMiss("c");
        verify(a, times(1)).recordMiss("c");
    }

    @Test
    public void noOpRecorder_doesNothing() {
        // 仅验证不抛异常
        NoOpCacheMetricsRecorder.INSTANCE.recordHit("c", "L1");
        NoOpCacheMetricsRecorder.INSTANCE.recordMiss("c");
        NoOpCacheMetricsRecorder.INSTANCE.recordEviction("c");
        NoOpCacheMetricsRecorder.INSTANCE.recordLatency("c", "get", 1, TimeUnit.SECONDS);
        assertThat(NoOpCacheMetricsRecorder.INSTANCE).isNotNull();
    }
}
