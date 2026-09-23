package io.github.nitouge.cache.core.management;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.impl.CompositeCache;
import io.github.nitouge.cache.core.metrics.CacheStatisticsAggregator;
import io.github.nitouge.cache.core.metrics.CacheStatisticsAggregator.CacheStats;
import io.github.nitouge.cache.core.metrics.CacheStatisticsAggregator.GlobalCacheStats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 组合多级缓存的 Actuator 管理端点（id = {@code compositecache}）。
 *
 * <p>opt-in：仅在引入 Spring Boot Actuator 且<b>显式暴露</b>后生效，例如：
 * <pre>management.endpoints.web.exposure.include=compositecache</pre>
 *
 * <h3>提供的操作</h3>
 * <ul>
 *   <li>{@code GET    /actuator/compositecache}        —— 全局命中率 + 各缓存概览（类型/L1 条目数/命中统计）</li>
 *   <li>{@code GET    /actuator/compositecache/{name}} —— 单个缓存详情</li>
 *   <li>{@code POST   /actuator/compositecache/{name}} —— 请求体 {@code {"key":"xxx"}} 逐出指定 key</li>
 *   <li>{@code DELETE /actuator/compositecache}        —— 清空所有缓存</li>
 *   <li>{@code DELETE /actuator/compositecache/{name}} —— 清空指定缓存</li>
 * </ul>
 *
 * <p>命中统计来自可选的 {@link CacheStatisticsAggregator}（未启用 metrics 时为 {@code null}，
 * 此时只返回缓存清单与可执行的写操作，不含 stats）。
 *
 * <p>设计对标 Spring 自带的 {@code CachesEndpoint}（同一端点上以 {@code @Selector} 参数数量区分读/删操作）。
 *
 * <p><b>未知 cacheName 的语义不统一</b>：读操作（{@link #cache(String)}）返回 {@code null} → HTTP 404；
 * 而 {@link #clear(String)} / {@link #evict(String, String)} 返回 {@code {error}} 体且 HTTP 200。
 * 此为运维端点，仅供人工或脚本调用，未做归一化处理。
 *
 */
@Slf4j
@Endpoint(id = "compositecache")
public class CompositeCacheEndpoint {

    private final CacheManager cacheManager;

    /**
     * 命中统计聚合器，可为 {@code null}（未启用 metrics 时）。
     */
    private final CacheStatisticsAggregator aggregator;

    public CompositeCacheEndpoint(CacheManager cacheManager, CacheStatisticsAggregator aggregator) {
        this.cacheManager = cacheManager;
        this.aggregator = aggregator;
    }

    /**
     * 概览：全局命中率 + 各缓存清单。
     */
    @ReadOperation
    public Map<String, Object> caches() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cacheManager", cacheManager.getClass().getSimpleName());
        if (aggregator != null) {
            result.put("global", toGlobalMap(aggregator.getGlobalStats()));
        }
        Map<String, Object> caches = new TreeMap<>();
        for (String name : cacheManager.getCacheNames()) {
            caches.put(name, describe(name));
        }
        result.put("cacheCount", caches.size());
        result.put("caches", caches);
        return result;
    }

    /**
     * 单个缓存详情；不存在时返回 {@code null}（HTTP 404）。
     */
    @ReadOperation
    public Map<String, Object> cache(@Selector String name) {
        if (!cacheManager.getCacheNames().contains(name)) {
            return null;
        }
        return describe(name);
    }

    /**
     * 清空所有缓存。
     */
    @DeleteOperation
    public Map<String, Object> clearAll() {
        int cleared = 0;
        for (String name : cacheManager.getCacheNames()) {
            Cache cache = cacheManager.getCache(name);
            if (cache != null) {
                cache.clear();
                cleared++;
            }
        }
        log.info("[composite-cache-endpoint] cleared all caches, count={}", cleared);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("clearedCaches", cleared);
        return r;
    }

    /**
     * 清空指定缓存。
     */
    @DeleteOperation
    public Map<String, Object> clear(@Selector String name) {
        Map<String, Object> r = new LinkedHashMap<>();
        Cache cache = cacheManager.getCache(name);
        if (cache == null) {
            r.put("error", "cache not found: " + name);
            return r;
        }
        cache.clear();
        log.info("[composite-cache-endpoint] cleared cache={}", name);
        r.put("cleared", name);
        return r;
    }

    /**
     * 逐出指定缓存中的某个 key（请求体提供 {@code key}）。
     */
    @WriteOperation
    public Map<String, Object> evict(@Selector String name, String key) {
        Map<String, Object> r = new LinkedHashMap<>();
        Cache cache = cacheManager.getCache(name);
        if (cache == null) {
            r.put("error", "cache not found: " + name);
            return r;
        }
        if (key == null) {
            r.put("error", "missing required parameter: key");
            return r;
        }
        cache.evict(key);
        log.info("[composite-cache-endpoint] evicted cache={}, key={}", name, key);
        r.put("cache", name);
        r.put("evicted", key);
        return r;
    }

    /**
     * 描述单个缓存：类型、是否允许 null 值、L1 条目数（best-effort）与命中统计。
     */
    private Map<String, Object> describe(String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        Cache cache = cacheManager.getCache(name);
        if (cache != null) {
            m.put("type", cache.getCacheType());
            m.put("allowNullValues", cache.isAllowNullValues());
            // L1 条目数：仅组合缓存可直接取，失败不影响其它字段
            if (cache instanceof CompositeCache) {
                try {
                    m.put("l1Size", ((CompositeCache) cache).getL1Cache().size());
                } catch (Exception ignore) {
                    // ignore：size 不可用时跳过
                }
            }
        }
        if (aggregator != null) {
            CacheStats stats = aggregator.getStats(name);
            if (stats != null) {
                m.put("stats", toStatsMap(stats));
            }
        }
        return m;
    }

    private Map<String, Object> toStatsMap(CacheStats s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hitCount", s.getHitCount());
        m.put("missCount", s.getMissCount());
        m.put("l1HitCount", s.getL1HitCount());
        m.put("l2HitCount", s.getL2HitCount());
        m.put("evictionCount", s.getEvictionCount());
        m.put("totalRequests", s.getTotalRequests());
        m.put("hitRate", round(s.getHitRate()));
        return m;
    }

    private Map<String, Object> toGlobalMap(GlobalCacheStats g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalHits", g.getTotalHits());
        m.put("totalMisses", g.getTotalMisses());
        m.put("totalRequests", g.getTotalRequests());
        m.put("hitRate", round(g.getHitRate()));
        return m;
    }

    /**
     * 命中率保留 4 位小数。
     */
    private double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
