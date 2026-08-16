package io.github.nitouge.cache.core.impl;

import io.github.nitouge.cache.core.api.CacheLoader;
import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.metrics.CacheMetricsRecorder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link CompositeCache} 中的命中/未命中统计。
 *
 * <p>P1-F 变更后的契约：在 LoadingCache 模式下，组合层仅记录
 * <b>真实的 L1 命中</b>；当 L1 未命中时，它既不记录命中也不记录未命中，而是将 L2 命中/未命中
 * 的归因委托给 L2 层（{@code RedissonRBucketCache.get(key, callable)}），这样由加载器解决的 L2 命中
 * 就不会再丢失（之前被错误地计为组合层未命中）。
 * 在非 LoadingCache 模式下，加载器路径不涉及 L2，因此组合层仍然自己记录未命中。
 *
 */
@ExtendWith(MockitoExtension.class)
public class CompositeCacheMetricsTest {

    @Mock
    private L1Cache l1Cache;

    @Mock
    private L2Cache l2Cache;

    @Mock
    private CacheConfig cacheConfig;

    @Mock
    private CacheMetricsRecorder cacheMetrics;

    private CompositeCache loadingCache() {
        when(l1Cache.isLoadingCache()).thenReturn(true);
        when(l1Cache.getCacheLoader()).thenReturn(mockLoader());
        CompositeCache cache = new CompositeCache("testCache", cacheConfig, l1Cache, l2Cache);
        cache.setMetricsRecorder(cacheMetrics);
        return cache;
    }

    private CacheLoader<Object, Object> mockLoader() {
        return Mockito.mock(CacheLoader.class);
    }

    @Test
    public void loadingGet_whenTrueL1Hit_shouldRecordL1Hit() {
        CompositeCache cache = loadingCache();
        // L1 真实命中：getIfPresent 返回值
        when(l1Cache.getIfPresent("k")).thenReturn("v");
        when(l1Cache.get("k")).thenReturn("v");

        Object result = cache.get("k");

        org.assertj.core.api.Assertions.assertThat(result).isEqualTo("v");
        verify(cacheMetrics, times(1)).recordHit("testCache", "L1");
        verify(cacheMetrics, never()).recordMiss("testCache");
    }

    @Test
    public void loadingGet_whenLoadedByLoader_shouldNotRecordAtCompositeLayer() {
        CompositeCache cache = loadingCache();
        // L1 未命中（getIfPresent 返回 null），但 LoadingCache.get 触发加载返回值
        when(l1Cache.getIfPresent("k")).thenReturn(null);
        when(l1Cache.get("k")).thenReturn("loadedFromDb");

        Object result = cache.get("k");

        org.assertj.core.api.Assertions.assertThat(result).isEqualTo("loadedFromDb");
        // 新契约：加载模式下 L1 未命中时，组合层既不虚报命中、也不在此记 miss——
        // L2 命中/回源未命中由 L2 层（RedissonRBucketCache.get(key,callable)）如实记录，避免漏掉 L2 命中。
        verify(cacheMetrics, never()).recordMiss("testCache");
        verify(cacheMetrics, never()).recordHit(eq("testCache"), any());
    }

    @Test
    public void getWithLoader_loadingMode_whenL1Miss_shouldNotRecordAtCompositeLayer() {
        CompositeCache cache = loadingCache();
        when(l1Cache.getIfPresent("k")).thenReturn(null);
        lenient().when(l1Cache.get(eq("k"), any(java.util.concurrent.Callable.class))).thenReturn("loaded");

        Object result = cache.get("k", () -> "loaded");

        org.assertj.core.api.Assertions.assertThat(result).isEqualTo("loaded");
        // 加载模式：未命中由 L2 层记录，组合层不记录（避免重复计数 / 漏记 L2 命中）
        verify(cacheMetrics, never()).recordMiss("testCache");
        verify(cacheMetrics, never()).recordHit(eq("testCache"), any());
    }

    @Test
    public void getWithLoader_nonLoadingMode_whenL1Miss_shouldRecordMissAtCompositeLayer() {
        // 非加载缓存：valueLoader 直达 DB，不经过 L2 加载器路径，故未命中由组合层记录
        when(l1Cache.isLoadingCache()).thenReturn(false);
        CompositeCache cache = new CompositeCache("testCache", cacheConfig, l1Cache, l2Cache);
        cache.setMetricsRecorder(cacheMetrics);
        when(l1Cache.getIfPresent("k")).thenReturn(null);
        lenient().when(l1Cache.get(eq("k"), any(java.util.concurrent.Callable.class))).thenReturn("loaded");

        Object result = cache.get("k", () -> "loaded");

        org.assertj.core.api.Assertions.assertThat(result).isEqualTo("loaded");
        verify(cacheMetrics, times(1)).recordMiss("testCache");
        verify(cacheMetrics, never()).recordHit(eq("testCache"), any());
    }

    @Test
    public void getWithLoader_whenL1Hit_shouldRecordL1Hit() {
        CompositeCache cache = loadingCache();
        when(l1Cache.getIfPresent("k")).thenReturn("cached");
        lenient().when(l1Cache.get(eq("k"), any(java.util.concurrent.Callable.class))).thenReturn("cached");

        cache.get("k", () -> "cached");

        verify(cacheMetrics, times(1)).recordHit("testCache", "L1");
        verify(cacheMetrics, never()).recordMiss("testCache");
    }
}
