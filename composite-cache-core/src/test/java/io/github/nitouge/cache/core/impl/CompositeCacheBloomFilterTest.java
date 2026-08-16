package io.github.nitouge.cache.core.impl;

import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.support.penetration.GuavaCacheBloomFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link CompositeCache} 与布隆过滤器（防穿透）的集成测试。
 *
 * <p>用 mock 的 L1 在 {@code get(key, callable)} 时执行被包装的加载器，模拟 L1+L2 未命中的回源时机，
 * 验证：未注册 → 正常回源；已注册且 key 不在过滤器 → 回源前被拦截（loader 不执行、返回 null）；
 * 已注册且 key 已知 → 正常回源。
 *
 */
@ExtendWith(MockitoExtension.class)
public class CompositeCacheBloomFilterTest {

    @Mock
    private L1Cache l1Cache;

    @Mock
    private L2Cache l2Cache;

    @SuppressWarnings("unchecked")
    private CompositeCache newCache(GuavaCacheBloomFilter bloom) {
        when(l1Cache.isLoadingCache()).thenReturn(false);
        CompositeCache cache = new CompositeCache("stock", new CacheConfig(), l1Cache, l2Cache);
        cache.setBloomFilter(bloom);
        // 模拟 L1+L2 未命中：l1Cache.get(key, callable) 直接执行（被布隆包装后的）加载器
        when(l1Cache.get(any(), any(Callable.class)))
                .thenAnswer(inv -> ((Callable<?>) inv.getArgument(1)).call());
        return cache;
    }

    @Test
    public void unregisteredCache_loaderRunsNormally() {
        GuavaCacheBloomFilter bloom = new GuavaCacheBloomFilter(1000, 0.01);
        CompositeCache cache = newCache(bloom);

        AtomicInteger dbCalls = new AtomicInteger();
        Object result = cache.get("1", () -> {
            dbCalls.incrementAndGet();
            return "v";
        });

        assertThat(result).isEqualTo("v");
        assertThat(dbCalls.get()).isEqualTo(1); // 未注册 → 不拦截，正常回源
    }

    @Test
    public void registeredCache_absentKeyBlockedBeforeDb() {
        GuavaCacheBloomFilter bloom = new GuavaCacheBloomFilter(1000, 0.01);
        bloom.register("stock", 1000, 0.01); // 已注册但未放入 "404"
        CompositeCache cache = newCache(bloom);

        AtomicInteger dbCalls = new AtomicInteger();
        Object result = cache.get("404", () -> {
            dbCalls.incrementAndGet();
            return "should-not-load";
        });

        assertThat(result).isNull();            // 被布隆拦截
        assertThat(dbCalls.get()).isEqualTo(0); // 未回源 DB
    }

    @Test
    public void registeredCache_knownKeyPassesAndLoads() {
        GuavaCacheBloomFilter bloom = new GuavaCacheBloomFilter(1000, 0.01);
        bloom.warmUp("stock", Arrays.asList("1"), 0.01); // "1" 为已知合法 key
        CompositeCache cache = newCache(bloom);

        AtomicInteger dbCalls = new AtomicInteger();
        Object result = cache.get("1", () -> {
            dbCalls.incrementAndGet();
            return "v";
        });

        assertThat(result).isEqualTo("v");
        assertThat(dbCalls.get()).isEqualTo(1); // 已知 key → 放行回源
    }
}
