package io.github.nitouge.cache.core.management;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.metrics.CacheStatisticsAggregator;
import io.github.nitouge.cache.core.metrics.CacheStatisticsAggregator.CacheStats;
import io.github.nitouge.cache.core.metrics.CacheStatisticsAggregator.GlobalCacheStats;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompositeCacheEndpoint} 单元测试：直接调用各操作方法（注解仅为 Actuator 元数据，不影响普通调用），
 * 校验缓存清单/详情、清空、逐出及命中统计的拼装逻辑。
 *
 */
public class CompositeCacheEndpointTest {

    @Test
    public void caches_withoutAggregator_listsCachesWithoutGlobal() {
        CacheManager cm = mock(CacheManager.class);
        Cache user = mock(Cache.class);
        when(user.getCacheType()).thenReturn("CAFFEINE + REDIS");
        when(user.isAllowNullValues()).thenReturn(true);
        when(cm.getCacheNames()).thenReturn(Collections.singletonList("user"));
        when(cm.getCache("user")).thenReturn(user);

        CompositeCacheEndpoint endpoint = new CompositeCacheEndpoint(cm, null);
        Map<String, Object> result = endpoint.caches();

        assertThat(result.get("cacheCount")).isEqualTo(1);
        assertThat(result).doesNotContainKey("global");
        @SuppressWarnings("unchecked")
        Map<String, Object> caches = (Map<String, Object>) result.get("caches");
        @SuppressWarnings("unchecked")
        Map<String, Object> u = (Map<String, Object>) caches.get("user");
        assertThat(u.get("type")).isEqualTo("CAFFEINE + REDIS");
        assertThat(u.get("allowNullValues")).isEqualTo(true);
        // 非 CompositeCache 的 mock 不应带 l1Size
        assertThat(u).doesNotContainKey("l1Size");
    }

    @Test
    public void caches_withAggregator_includesGlobalAndPerCacheStats() {
        CacheManager cm = mock(CacheManager.class);
        Cache user = mock(Cache.class);
        when(user.getCacheType()).thenReturn("CAFFEINE + REDIS");
        when(user.isAllowNullValues()).thenReturn(false);
        when(cm.getCacheNames()).thenReturn(Collections.singletonList("user"));
        when(cm.getCache("user")).thenReturn(user);

        CacheStatisticsAggregator aggregator = mock(CacheStatisticsAggregator.class);
        when(aggregator.getGlobalStats()).thenReturn(new GlobalCacheStats(8, 2));
        CacheStats userStats = new CacheStats("user");
        userStats.addHit("L1", 5);
        userStats.addHit("L2", 1);
        userStats.addMiss(2);
        when(aggregator.getStats("user")).thenReturn(userStats);

        CompositeCacheEndpoint endpoint = new CompositeCacheEndpoint(cm, aggregator);
        Map<String, Object> result = endpoint.caches();

        @SuppressWarnings("unchecked")
        Map<String, Object> global = (Map<String, Object>) result.get("global");
        assertThat(global.get("totalHits")).isEqualTo(8L);
        assertThat(global.get("totalMisses")).isEqualTo(2L);
        assertThat(global.get("hitRate")).isEqualTo(0.8);

        @SuppressWarnings("unchecked")
        Map<String, Object> u = (Map<String, Object>) ((Map<String, Object>) result.get("caches")).get("user");
        @SuppressWarnings("unchecked")
        Map<String, Object> stats = (Map<String, Object>) u.get("stats");
        assertThat(stats.get("hitCount")).isEqualTo(6L);
        assertThat(stats.get("l1HitCount")).isEqualTo(5L);
        assertThat(stats.get("l2HitCount")).isEqualTo(1L);
        assertThat(stats.get("missCount")).isEqualTo(2L);
    }

    @Test
    public void cache_unknownName_returnsNull() {
        CacheManager cm = mock(CacheManager.class);
        when(cm.getCacheNames()).thenReturn(Collections.emptyList());

        CompositeCacheEndpoint endpoint = new CompositeCacheEndpoint(cm, null);
        assertThat(endpoint.cache("nope")).isNull();
    }

    @Test
    public void clear_existingCache_clearsAndReports() {
        CacheManager cm = mock(CacheManager.class);
        Cache user = mock(Cache.class);
        when(cm.getCache("user")).thenReturn(user);

        CompositeCacheEndpoint endpoint = new CompositeCacheEndpoint(cm, null);
        Map<String, Object> r = endpoint.clear("user");

        verify(user).clear();
        assertThat(r.get("cleared")).isEqualTo("user");
    }

    @Test
    public void clear_missingCache_returnsError() {
        CacheManager cm = mock(CacheManager.class);
        when(cm.getCache("x")).thenReturn(null);

        CompositeCacheEndpoint endpoint = new CompositeCacheEndpoint(cm, null);
        Map<String, Object> r = endpoint.clear("x");

        assertThat(r).containsKey("error");
    }

    @Test
    public void clearAll_clearsEveryCache() {
        CacheManager cm = mock(CacheManager.class);
        Cache a = mock(Cache.class);
        Cache b = mock(Cache.class);
        when(cm.getCacheNames()).thenReturn(Arrays.asList("a", "b"));
        when(cm.getCache("a")).thenReturn(a);
        when(cm.getCache("b")).thenReturn(b);

        CompositeCacheEndpoint endpoint = new CompositeCacheEndpoint(cm, null);
        Map<String, Object> r = endpoint.clearAll();

        verify(a).clear();
        verify(b).clear();
        assertThat(r.get("clearedCaches")).isEqualTo(2);
    }

    @Test
    public void evict_existingCacheAndKey_evicts() {
        CacheManager cm = mock(CacheManager.class);
        Cache user = mock(Cache.class);
        when(cm.getCache("user")).thenReturn(user);

        CompositeCacheEndpoint endpoint = new CompositeCacheEndpoint(cm, null);
        Map<String, Object> r = endpoint.evict("user", "k1");

        verify(user).evict("k1");
        assertThat(r.get("evicted")).isEqualTo("k1");
        assertThat(r.get("cache")).isEqualTo("user");
    }

    @Test
    public void evict_missingKey_returnsError() {
        CacheManager cm = mock(CacheManager.class);
        when(cm.getCache("user")).thenReturn(mock(Cache.class));

        CompositeCacheEndpoint endpoint = new CompositeCacheEndpoint(cm, null);
        Map<String, Object> r = endpoint.evict("user", null);

        assertThat(r).containsKey("error");
    }
}
