package io.github.nitouge.cache.core.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 缓存监控指标收集器
 *
 * <p>集成Micrometer监控框架，提供缓存性能指标：
 * <ul>
 *   <li>缓存命中率（hit rate）</li>
 *   <li>缓存未命中率（miss rate）</li>
 *   <li>缓存操作耗时（latency）</li>
 *   <li>缓存容量（size）</li>
 *   <li>缓存驱逐次数（eviction count）</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 记录缓存命中
 * cacheMetrics.recordHit("user");
 *
 * // 记录缓存未命中
 * cacheMetrics.recordMiss("user");
 *
 * // 记录操作耗时
 * cacheMetrics.recordLatency("user", "get", 10, TimeUnit.MILLISECONDS);
 *
 * // 记录缓存大小
 * cacheMetrics.recordSize("user", 1000);
 * }</pre>
 *
 * <h3>Prometheus查询示例</h3>
 * <pre>
 * # 缓存命中率
 * rate(cache_hit_total[5m]) / (rate(cache_hit_total[5m]) + rate(cache_miss_total[5m]))
 *
 * # 平均响应时间
 * rate(cache_latency_seconds_sum[5m]) / rate(cache_latency_seconds_count[5m])
 *
 * # 缓存容量
 * cache_size
 * </pre>
 *
 */
@Slf4j
public class CacheMetrics implements CacheMetricsRecorder {

    private final MeterRegistry registry;

    /**
     * 缓存命中计数器
     */
    private final Map<String, Counter> hitCounters = new ConcurrentHashMap<>();

    /**
     * 缓存未命中计数器
     */
    private final Map<String, Counter> missCounters = new ConcurrentHashMap<>();

    /**
     * 缓存驱逐计数器
     */
    private final Map<String, Counter> evictionCounters = new ConcurrentHashMap<>();

    /**
     * 缓存操作耗时计时器
     */
    private final Map<String, Timer> latencyTimers = new ConcurrentHashMap<>();

    /**
     * 回源加载耗时计时器（按成功/失败区分）
     */
    private final Map<String, Timer> loadTimers = new ConcurrentHashMap<>();

    /**
     * 操作异常计数器
     */
    private final Map<String, Counter> exceptionCounters = new ConcurrentHashMap<>();

    /**
     * 是否启用监控
     */
    @Getter
    private final boolean enabled;

    public CacheMetrics(MeterRegistry registry) {
        this(registry, true);
    }

    public CacheMetrics(MeterRegistry registry, boolean enabled) {
        this.registry = registry;
        this.enabled = enabled;
        if (enabled) {
            log.info("Cache metrics enabled");
        }
    }

    /**
     * 记录缓存命中
     *
     * @param cacheName 缓存名称
     */
    public void recordHit(String cacheName) {
        recordHit(cacheName, "unknown");
    }

    /**
     * 记录缓存命中（带缓存级别）
     *
     * @param cacheName 缓存名称
     * @param level     缓存级别（L1/L2）
     */
    public void recordHit(String cacheName, String level) {
        if (!enabled) {
            return;
        }
        String key = cacheName + ":" + level;
        hitCounters.computeIfAbsent(key, k ->
                Counter.builder("cache.hit")
                        .tag("cache", cacheName)
                        .tag("level", level)
                        .description("Cache hit count")
                        .register(registry)
        ).increment();
    }

    /**
     * 记录缓存未命中
     *
     * @param cacheName 缓存名称
     */
    public void recordMiss(String cacheName) {
        if (!enabled) {
            return;
        }
        missCounters.computeIfAbsent(cacheName, name ->
                Counter.builder("cache.miss")
                        .tag("cache", name)
                        .description("Cache miss count")
                        .register(registry)
        ).increment();
    }

    /**
     * 记录缓存驱逐
     *
     * @param cacheName 缓存名称
     */
    public void recordEviction(String cacheName) {
        if (!enabled) {
            return;
        }
        evictionCounters.computeIfAbsent(cacheName, name ->
                Counter.builder("cache.eviction")
                        .tag("cache", name)
                        .description("Cache eviction count")
                        .register(registry)
        ).increment();
    }

    @Override
    public void recordHit(String cacheName, String level, int count) {
        if (!enabled || count <= 0) {
            return;
        }
        String key = cacheName + ":" + level;
        hitCounters.computeIfAbsent(key, k ->
                Counter.builder("cache.hit")
                        .tag("cache", cacheName)
                        .tag("level", level)
                        .description("Cache hit count")
                        .register(registry)
        ).increment(count);
    }

    @Override
    public void recordMiss(String cacheName, int count) {
        if (!enabled || count <= 0) {
            return;
        }
        missCounters.computeIfAbsent(cacheName, name ->
                Counter.builder("cache.miss")
                        .tag("cache", name)
                        .description("Cache miss count")
                        .register(registry)
        ).increment(count);
    }

    @Override
    public void recordEviction(String cacheName, int count) {
        if (!enabled || count <= 0) {
            return;
        }
        evictionCounters.computeIfAbsent(cacheName, name ->
                Counter.builder("cache.eviction")
                        .tag("cache", name)
                        .description("Cache eviction count")
                        .register(registry)
        ).increment(count);
    }

    /**
     * 记录缓存操作耗时
     *
     * @param cacheName 缓存名称
     * @param operation 操作类型（get、put、evict等）
     * @param duration  耗时
     * @param unit      时间单位
     */
    public void recordLatency(String cacheName, String operation, long duration, TimeUnit unit) {
        if (!enabled) {
            return;
        }
        String key = cacheName + ":" + operation;
        latencyTimers.computeIfAbsent(key, k ->
                Timer.builder("cache.latency")
                        .tag("cache", cacheName)
                        .tag("operation", operation)
                        .description("Cache operation latency")
                        .register(registry)
        ).record(duration, unit);
    }

    /**
     * 记录一次回源加载（L2 未命中回源 DB）的耗时与成败。
     */
    @Override
    public void recordLoad(String cacheName, long duration, TimeUnit unit, boolean success) {
        if (!enabled) {
            return;
        }
        String key = cacheName + ":" + success;
        loadTimers.computeIfAbsent(key, k ->
                Timer.builder("cache.load")
                        .tag("cache", cacheName)
                        .tag("success", String.valueOf(success))
                        .description("Cache load (origin fetch) latency")
                        .register(registry)
        ).record(duration, unit);
    }

    /**
     * 记录一次操作异常。
     */
    @Override
    public void recordException(String cacheName, String operation) {
        if (!enabled) {
            return;
        }
        String key = cacheName + ":" + operation;
        exceptionCounters.computeIfAbsent(key, k ->
                Counter.builder("cache.exception")
                        .tag("cache", cacheName)
                        .tag("operation", operation == null ? "unknown" : operation)
                        .description("Cache operation exception count")
                        .register(registry)
        ).increment();
    }

    /**
     * 记录缓存大小
     *
     * <p><b>未接线的可选扩展点</b>：该方法不在 {@link CacheMetricsRecorder} 接口上，
     * 框架不会自动调用。如需采集 {@code cache.size} 指标，须自行持有 {@link CacheMetrics} 实例并主动调用注册。
     *
     * @param cacheName    缓存名称
     * @param sizeSupplier 缓存大小提供者
     */
    public void registerCacheSize(String cacheName, java.util.function.Supplier<Number> sizeSupplier) {
        if (!enabled) {
            return;
        }
        Gauge.builder("cache.size", sizeSupplier)
                .tag("cache", cacheName)
                .description("Cache size")
                .register(registry);
    }

    /**
     * 记录缓存容量
     *
     * <p><b>未接线的可选扩展点</b>：该方法不在 {@link CacheMetricsRecorder} 接口上，
     * 框架不会自动调用。如需采集 {@code cache.capacity} 指标，须自行持有 {@link CacheMetrics} 实例并主动调用注册。
     *
     * @param cacheName        缓存名称
     * @param capacitySupplier 缓存容量提供者
     */
    public void registerCacheCapacity(String cacheName, java.util.function.Supplier<Number> capacitySupplier) {
        if (!enabled) {
            return;
        }
        Gauge.builder("cache.capacity", capacitySupplier, supplier -> supplier.get().doubleValue())
                .tag("cache", cacheName)
                .description("Cache capacity")
                .register(registry);
    }

    /**
     * 获取缓存命中率
     *
     * @param cacheName 缓存名称
     * @return 命中率（0.0-1.0），如果没有数据返回-1
     */
    public double getHitRate(String cacheName) {
        if (!enabled) {
            return -1;
        }
        // 命中计数器按 "cacheName:level"（L1/L2/...）分桶，需累加该缓存名下所有级别的命中数；
        // 未命中计数器按 cacheName 直接分桶。
        String hitPrefix = cacheName + ":";
        double hits = 0;
        boolean anyHit = false;
        for (Map.Entry<String, Counter> entry : hitCounters.entrySet()) {
            if (entry.getKey().startsWith(hitPrefix)) {
                hits += entry.getValue().count();
                anyHit = true;
            }
        }
        Counter missCounter = missCounters.get(cacheName);
        if (!anyHit && missCounter == null) {
            return -1;
        }
        double misses = missCounter != null ? missCounter.count() : 0;
        double total = hits + misses;
        return total > 0 ? hits / total : 0;
    }

    /**
     * 清除指定缓存的监控指标
     *
     * @param cacheName 缓存名称
     */
    public void clear(String cacheName) {
        if (!enabled) {
            return;
        }
        String prefix = cacheName + ":";
        // hit/latency/load/exception 计数器按 "cacheName:xxx" 分桶（旧实现用 remove(cacheName) 永远删不掉）；
        // miss/eviction 按 cacheName 直接分桶。移除映射的同时从 registry 注销对应 meter，避免 counter/timer 泄漏。
        removeByPrefix(hitCounters, prefix);
        removeByPrefix(latencyTimers, prefix);
        removeByPrefix(loadTimers, prefix);
        removeByPrefix(exceptionCounters, prefix);
        removeExact(missCounters, cacheName);
        removeExact(evictionCounters, cacheName);
    }

    /**
     * 移除 key 以指定前缀开头的所有 meter，并从 registry 注销，避免泄漏。
     */
    private void removeByPrefix(Map<String, ? extends io.micrometer.core.instrument.Meter> meters, String prefix) {
        meters.entrySet().removeIf(entry -> {
            if (entry.getKey().startsWith(prefix)) {
                registry.remove(entry.getValue());
                return true;
            }
            return false;
        });
    }

    /**
     * 按精确 key 移除一个 meter，并从 registry 注销。
     */
    private void removeExact(Map<String, ? extends io.micrometer.core.instrument.Meter> meters, String key) {
        io.micrometer.core.instrument.Meter meter = meters.remove(key);
        if (meter != null) {
            registry.remove(meter);
        }
    }

}
