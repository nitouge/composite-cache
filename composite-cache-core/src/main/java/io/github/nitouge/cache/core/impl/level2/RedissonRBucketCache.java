package io.github.nitouge.cache.core.impl.level2;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Lists;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L2CacheSetting;
import io.github.nitouge.cache.core.consts.CacheConsts;
import io.github.nitouge.cache.core.consts.enums.CacheTypeEnum;
import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;
import io.github.nitouge.cache.core.exception.RedisTryLockFailException;
import io.github.nitouge.cache.core.impl.base.AbstractAdaptingCache;
import io.github.nitouge.cache.core.loader.task.LoadValueTask;
import io.github.nitouge.cache.core.loader.task.PublishMessageTask;
import io.github.nitouge.cache.core.metrics.CacheMetricsRecorder;
import io.github.nitouge.cache.core.metrics.NoOpCacheMetricsRecorder;
import io.github.nitouge.cache.core.pool.ThreadPoolRegistry;
import io.github.nitouge.cache.core.support.degrade.L2CircuitBreaker;
import io.github.nitouge.cache.core.support.log.CacheAccessLog;
import io.github.nitouge.cache.core.support.mdc.MdcBiConsumerWrapper;
import io.github.nitouge.cache.core.util.RandomUtil;
import io.github.nitouge.cache.core.wrapper.LogicalExpireWrapper;
import io.github.nitouge.cache.core.wrapper.NullValueWrapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBatch;
import org.redisson.api.RBucket;
import org.redisson.api.RFuture;
import org.redisson.api.RKeys;
import org.redisson.api.RLock;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 基于 Redisson RBucket 实现的 L2（远程）缓存。
 *
 * <h2>核心特性</h2>
 * <ul>
 *   <li><b>三种回源策略</b>：
 *     <ul>
 *       <li>NONE：无锁回源，适用于低并发或允许短暂重复加载的场景</li>
 *       <li>LOCK：分布式锁 + 本地锁双重保护，避免缓存击穿</li>
 *       <li>LOGICAL_EXPIRE：逻辑过期，永不删除旧值，异步刷新，极致可用性</li>
 *     </ul>
 *   </li>
 *   <li><b>降级熔断</b>：当 Redis 故障时自动降级到直接回源，熔断器保护系统稳定性</li>
 *   <li><b>TTL 抖动</b>：自动为缓存过期时间添加随机抖动，防止缓存雪崩</li>
 *   <li><b>批量操作分片</b>：批量读写自动分片，避免单批次操作过大</li>
 *   <li><b>指标记录</b>：支持命中率、异常等指标统计（可选）</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * 所有公共方法均线程安全。内部使用：
 * <ul>
 *   <li>本地 Caffeine 锁缓存（有界自动淘汰）减少分布式锁竞争</li>
 *   <li>Redisson 分布式锁保证跨节点互斥</li>
 *   <li>线程安全的 Map 实现（如 ConcurrentHashMap）用于批量操作结果聚合</li>
 * </ul>
 *
 * @see RedisLoadStrategyEnum
 * @see L2CircuitBreaker
 */
@Slf4j
public class RedissonRBucketCache extends AbstractAdaptingCache implements L2Cache {

    // ==================== 常量定义 ====================

    /** 本地锁缓存最大容量：防止内存泄漏，自动淘汰最久未用的锁 */
    private static final int LOCAL_LOCK_MAX_SIZE = 10_000;

    /** 异常堆栈追溯最大深度：避免无限循环 */
    private static final int MAX_CAUSE_DEPTH = 16;

    /** 逻辑过期首次加载锁的等待超时时间（秒） */
    private static final int LOGICAL_FIRST_LOAD_TIMEOUT_SECONDS = 10;

    // ==================== 配置与依赖 ====================

    /** Redis 配置：包含批量大小、TTL 抖动、降级开关等 */
    private final CacheConfig.RedisConfig redisConfig;

    /** L2 缓存设置：过期时间、时间单位等 */
    private final L2CacheSetting l2CacheSetting;

    /** Redisson 客户端：操作 Redis 的核心入口 */
    private final RedissonClient redissonClient;

    /** 回源策略：NONE / LOCK / LOGICAL_EXPIRE */
    private final RedisLoadStrategyEnum loadStrategy;

    /** LOCK 策略专用分布式锁容器；非 LOCK 策略时为 null */
    private final RMap<Object, Object> distributedLockMap;

    // ==================== 指标与降级 ====================

    /**
     * 指标记录器：记录命中率、未命中、异常等。
     * 默认 NoOp，由 CacheManager 在独立 L2 部署时注入真实实现。
     */
    private volatile CacheMetricsRecorder metricsRecorder = NoOpCacheMetricsRecorder.INSTANCE;

    /** 降级开关：是否启用熔断器 */
    private final boolean degradeEnabled;

    /** 熔断器：根据失败率自动打开/半开/关闭，保护后端稳定性 */
    private final L2CircuitBreaker breaker;

    /**
     * 降级时的并发加载控制信号量：限制降级时直接回源的并发数，防止压垮数据源。
     * 若 {@code degradeMaxConcurrentLoads <= 0} 则为 null（不限流）。
     */
    private final Semaphore degradeSemaphore;

    // ==================== 线程池与本地锁 ====================

    /**
     * 逻辑过期异步刷新线程池：当检测到逻辑过期的缓存时，
     * 在后台线程中异步加载新值，主线程继续返回旧值（保证可用性）。
     */
    private static final ThreadPoolExecutor LOGICAL_REFRESH_POOL = ThreadPoolRegistry.getPool("logical_expire_refresh");

    /**
     * JVM 本地锁缓存（单飞锁）：在 LOCK 策略下，先竞争本地锁再竞争分布式锁，
     * 减少分布式锁网络竞争。有界自动淘汰，防止内存泄漏。
     */
    private final Cache<Object, ReentrantLock> localLocks = Caffeine.newBuilder()
            .maximumSize(LOCAL_LOCK_MAX_SIZE)
            .build();

    // ==================== 构造 ====================

    /**
     * 构造 RedissonRBucketCache 实例。
     *
     * @param cacheName      缓存名称（用于日志、指标、key 前缀等）
     * @param cacheConfig    缓存配置（包含 Redis 配置、降级配置等）
     * @param cacheSetting   缓存设置（包含 L2 过期时间等）
     * @param redissonClient Redisson 客户端实例
     */
    public RedissonRBucketCache(String cacheName,
                                CacheConfig cacheConfig,
                                CacheSetting cacheSetting,
                                RedissonClient redissonClient) {
        super(cacheName, cacheConfig);
        this.redisConfig = cacheConfig.getRedis();
        this.l2CacheSetting = cacheSetting.getL2CacheSetting();
        this.redissonClient = redissonClient;

        // 策略解析优先级：注解级 > CacheName 级 > 全局
        this.loadStrategy = resolveLoadStrategy(l2CacheSetting, redisConfig);

        // LOCK 策略需要分布式锁容器，其他策略不需要
        this.distributedLockMap = (this.loadStrategy == RedisLoadStrategyEnum.LOCK)
                ? redissonClient.getMap(cacheName) : null;

        // 初始化降级熔断器
        this.degradeEnabled = redisConfig.isDegradeEnabled();
        this.breaker = new L2CircuitBreaker(degradeEnabled,
                redisConfig.getDegradeFailureThreshold(), redisConfig.getDegradeOpenMillis());

        // 初始化降级并发控制信号量（若配置值 <= 0 则不限流）
        int maxLoads = redisConfig.getDegradeMaxConcurrentLoads();
        this.degradeSemaphore = (degradeEnabled && maxLoads > 0) ? new Semaphore(maxLoads) : null;

        log.info("[RedissonRBucketCache] init, cacheName={}, loadStrategy={}, degradeEnabled={}",
                cacheName, loadStrategy, degradeEnabled);
    }

    /**
     * 解析回源策略（优先级：注解级 > CacheName 级 > 全局）
     */
    private RedisLoadStrategyEnum resolveLoadStrategy(L2CacheSetting l2Setting, CacheConfig.RedisConfig redisConfig) {
        // 1. 优先使用注解级配置
        if (l2Setting != null && l2Setting.getLoadStrategy() != null
                && !l2Setting.getLoadStrategy().needResolve()) {
            return l2Setting.getLoadStrategy();
        }

        // 2. 回退到 CacheName 级或全局配置
        return redisConfig.getEffectiveLoadStrategy();
    }

    /**
     * 解析逻辑过期物理 TTL 倍数（优先级：注解级 > 全局）
     */
    private int resolveLogicalExpirePhysicalTtlFactor() {
        // 1. 优先使用注解级配置
        if (l2CacheSetting != null && l2CacheSetting.getLogicalExpirePhysicalTtlFactor() != null
                && l2CacheSetting.getLogicalExpirePhysicalTtlFactor() >= 0) {
            return l2CacheSetting.getLogicalExpirePhysicalTtlFactor();
        }

        // 2. 回退到全局配置
        return redisConfig.getLogicalExpirePhysicalTtlFactor();
    }

    /**
     * 注入指标记录器（由 CacheManager 在独立 L2 部署时调用）。
     * 作为 CompositeCache 内层 L2 时保持 NoOp，避免与组合层重复计数。
     *
     * @param metricsRecorder 指标记录器实现
     */
    public void setMetricsRecorder(CacheMetricsRecorder metricsRecorder) {
        if (metricsRecorder != null) {
            this.metricsRecorder = metricsRecorder;
        }
    }

    // ==================== L2Cache 元信息 ====================

    /**
     * 获取缓存过期时间（数值部分）。
     *
     * @return 过期时间数值
     */
    @Override
    public long getExpireTime() {
        return l2CacheSetting.getExpireTime();
    }

    /**
     * 获取缓存过期时间单位。
     *
     * @return 时间单位（秒/毫秒/分钟等）
     */
    @Override
    public TimeUnit getExpireTimeUnit() {
        return l2CacheSetting.getExpireTimeUnit();
    }

    /**
     * 获取缓存类型标识。
     *
     * @return 固定返回 "REDIS"
     */
    @Override
    public String getCacheType() {
        return CacheTypeEnum.REDIS.name();
    }

    /**
     * 获取底层 Redisson 客户端实例。
     *
     * @return RedissonClient 实例
     */
    @Override
    public RedissonClient getActualCache() {
        return redissonClient;
    }

    /**
     * 构建 Redis 缓存 key。
     *
     * <p>根据配置决定是否添加缓存名前缀：
     * <ul>
     *   <li>若 {@code userPrefix=true}：返回 {@code cacheName:key}</li>
     *   <li>若 {@code userPrefix=false}：直接返回 {@code key}</li>
     * </ul>
     *
     * @param key 业务 key（不能为 null 或空字符串）
     * @return Redis 中实际存储的 key
     * @throws IllegalArgumentException 若 key 为 null 或空
     */
    @Override
    public String buildKey(Object key) {
        if (key == null || "".equals(key)) {
            throw new IllegalArgumentException("key must not be null or empty");
        }
        return redisConfig.isUserPrefix()
                ? getCacheName() + CacheConsts.SPLIT_MULTI + key
                : String.valueOf(key);
    }

    // ==================== 读操作 ====================

    /**
     * 从缓存中获取值（无回源）。
     *
     * <p>若缓存命中则返回值，未命中返回 null。
     * 支持降级熔断：Redis 故障时返回 null 而不抛异常。
     *
     * @param key 缓存 key
     * @return 缓存值，未命中或降级时返回 null
     */
    @Override
    public Object get(Object key) {
        String cacheKey = buildKey(key);
        Object value = l2Read(() -> unwrap(getBucket(cacheKey).get()), null);
        log.debug("[RedissonRBucketCache] get, cacheName={}, key={}", getCacheName(), cacheKey);
        return fromCacheValue(value);
    }

    /**
     * 从缓存中获取值并进行类型检查。
     *
     * @param key  缓存 key
     * @param type 期望的值类型
     * @param <T>  值类型
     * @return 缓存值，未命中返回 null
     * @throws IllegalStateException 若缓存值类型与期望类型不匹配
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Object key, Class<T> type) {
        Object value = get(key);
        if (value == null) {
            return null;
        }
        if (type != null && !type.isInstance(value)) {
            throw new IllegalStateException(
                    "[RedissonRBucketCache] Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    /**
     * 从缓存中获取值，未命中时使用 valueLoader 回源加载并写入缓存。
     *
     * <p><b>核心流程</b>：
     * <ol>
     *   <li>检查熔断器状态，若打开则直接降级回源</li>
     *   <li>尝试从 Redis 读取值</li>
     *   <li>命中：根据策略处理（逻辑过期检查、直接返回等）</li>
     *   <li>未命中：根据 loadStrategy 选择回源方式（NONE / LOCK / LOGICAL_EXPIRE）</li>
     *   <li>回源成功后写入缓存并返回</li>
     * </ol>
     *
     * <p><b>降级保护</b>：
     * <ul>
     *   <li>Redis 连接异常时自动降级到直接回源</li>
     *   <li>降级期间可选限流（通过 degradeSemaphore 控制并发数）</li>
     * </ul>
     *
     * @param key         缓存 key
     * @param valueLoader 回源加载器（未命中时调用）
     * @param <T>         值类型
     * @return 缓存值或回源加载的值
     * @throws RuntimeException 若回源加载失败
     */
    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        // 熔断打开：直接降级回源
        if (degradeEnabled && !breaker.allowRequest()) {
            return (T) degraded(key, valueLoader);
        }
        try {
            T result = doGetWithLoader(key, valueLoader);
            if (degradeEnabled) {
                breaker.onSuccess();
            }
            return result;
        } catch (RuntimeException ex) {
            if (degradeEnabled && containsRedisError(ex)) {
                breaker.onFailure();
                log.warn("[L2 degrade] redis error in get(key,callable), cacheName={}, key={}",
                        getCacheName(), key, ex);
                return (T) degraded(key, valueLoader);
            }
            if (degradeEnabled) {
                breaker.onSuccess();
            }
            throw ex;
        }
    }

    // ==================== 写操作 ====================

    @Override
    public void put(Object key, Object value) {
        l2Write("put", () -> doPut(key, value));
    }

    @Override
    public Object putIfAbsent(Object key, Object value) {
        if (!isAllowNullValues() && value == null) {
            return get(key);
        }
        return l2Read(() -> doPutIfAbsent(key, value), null);
    }

    @Override
    public void evict(Object key) {
        l2Write("evict", () -> {
            String cacheKey = buildKey(key);
            boolean result = getBucket(cacheKey).delete();
            log.debug("evict, cacheName={}, key={}, result={}", getCacheName(), cacheKey, result);
        });
    }

    @Override
    public void clear() {
        if (!redisConfig.isUserPrefix()) {
            log.warn("[RedissonRBucketCache] clear() skipped: requires userPrefix=true. cacheName={}", getCacheName());
            return;
        }
        String pattern = getCacheName() + CacheConsts.SPLIT_MULTI + CacheConsts.ASTERISK;
        log.warn("clear start (SCAN-based), pattern={}", pattern);
        l2Write("clear", () -> {
            RKeys keys = redissonClient.getKeys();
            long count = keys.deleteByPattern(pattern);
            log.warn("clear end, pattern={}, deleteCount={}", pattern, count);
        });
    }

    @Override
    public boolean isExists(Object key) {
        String cacheKey = buildKey(key);
        boolean result = l2Read(() -> getBucket(cacheKey).isExists(), false);
        log.debug("isExists, cacheName={}, key={}, result={}", getCacheName(), cacheKey, result);
        return result;
    }

    // ==================== 批量操作 ====================

    @Override
    public <K, V> Map<K, V> batchGet(Map<K, Object> keyMap, boolean returnNullValueKey) {
        Map<K, V> fallback = Collections.emptyMap();
        return l2Read(() -> doBatchGet(keyMap, returnNullValueKey), fallback);
    }

    @Override
    public <V> void batchPut(Map<Object, V> dataMap) {
        if (dataMap == null || dataMap.isEmpty()) {
            return;
        }
        l2Write("batchPut", () -> doBatchPut(dataMap));
    }

    @Override
    public <K> void batchEvict(Map<K, Object> keyMap) {
        if (keyMap == null || keyMap.isEmpty()) {
            return;
        }
        l2Write("batchEvict", () -> doBatchEvict(keyMap));
    }

    // ==================== 降级状态查询 ====================

    public L2CircuitBreaker.State getL2DegradeState() {
        return degradeEnabled ? breaker.state() : null;
    }

    // =====================================================================
    //                          私有核心实现
    // =====================================================================

    private RBucket<Object> getBucket(String cacheKey) {
        return redissonClient.getBucket(cacheKey);
    }

    private boolean isLogicalExpire() {
        return loadStrategy == RedisLoadStrategyEnum.LOGICAL_EXPIRE;
    }

    /** 解包逻辑过期包装器；非包装值原样返回（兼容策略切换前历史数据） */
    private Object unwrap(Object raw) {
        return (raw instanceof LogicalExpireWrapper) ? ((LogicalExpireWrapper) raw).getData() : raw;
    }

    // ---------- get(key, callable) 核心流程 ----------

    @SuppressWarnings("unchecked")
    private <T> T doGetWithLoader(Object key, Callable<T> valueLoader) {
        String cacheKey = buildKey(key);
        RBucket<Object> bucket = getBucket(cacheKey);
        Object raw = bucket.get();

        // 命中
        if (raw != null) {
            metricsRecorder.recordHit(getCacheName(), "L2");
            CacheAccessLog.hitL2(getCacheName(), key);

            if (isLogicalExpire() && raw instanceof LogicalExpireWrapper) {
                LogicalExpireWrapper wrapper = (LogicalExpireWrapper) raw;
                if (!wrapper.isExpired()) {
                    log.debug("[RedissonRBucketCache] logical-expire hit (fresh), cacheName={}, key={}", getCacheName(), cacheKey);
                    return (T) fromCacheValue(wrapper.getData());
                }
                log.debug("[RedissonRBucketCache] logical-expire stale, async refresh, cacheName={}, key={}", getCacheName(), cacheKey);
                triggerAsyncRefresh(key, cacheKey, valueLoader);
                return (T) fromCacheValue(wrapper.getData());
            }

            log.debug("[RedissonRBucketCache] get(key,callable) hit, cacheName={}, key={}", getCacheName(), cacheKey);
            return (T) fromCacheValue(unwrap(raw));
        }

        // 未命中
        metricsRecorder.recordMiss(getCacheName());
        if (valueLoader == null || !hasEffectiveLoader(valueLoader)) {
            log.debug("[RedissonRBucketCache] get(key,callable) no effective loader, cacheName={}, key={}", getCacheName(), cacheKey);
            return null;
        }

        CacheAccessLog.loadFromSource(getCacheName(), key, loadStrategy);
        switch (loadStrategy) {
            case LOCK:
                return (T) loadWithLock(key, cacheKey, bucket, valueLoader);
            case LOGICAL_EXPIRE:
                return (T) loadFirstTimeLogical(key, cacheKey, bucket, valueLoader);
            default:
                return (T) loadWithoutLock(key, cacheKey, valueLoader);
        }
    }

    // ---------- 三种回源策略 ----------

    private Object loadWithoutLock(Object key, String cacheKey, Callable<?> valueLoader) {
        try {
            Object value = valueLoader.call();
            suppressPublishIfNull(value, valueLoader);
            put(key, value);
            return fromCacheValue(value);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load value for key: " + key, ex);
        }
    }

    private Object loadWithLock(Object key, String cacheKey, RBucket<Object> bucket, Callable<?> valueLoader) {
        boolean tryLock = redisConfig.isTryLock();
        ReentrantLock localLock = localLocks.get(key, k -> new ReentrantLock());

        // 防御性判空
        if (localLock == null) {
            log.error("Failed to obtain local lock instance, cacheName={}, key={}", getCacheName(), cacheKey);
            throw new IllegalStateException("Local lock instance is null for key: " + key);
        }

        // 本地锁
        boolean localLocked = tryLock ? localLock.tryLock() : acquireLockSafely(localLock);
        if (tryLock && !localLocked) {
            log.warn("Local tryLock fast-fail, cacheName={}, key={}", getCacheName(), cacheKey);
            throw new RedisTryLockFailException("Local tryLock fast-fail, key=" + cacheKey);
        }

        try {
            // 双重检查
            Object value = unwrap(bucket.get());
            if (value != null) {
                return fromCacheValue(value);
            }

            // 分布式锁
            RLock distLock = (distributedLockMap != null) ? distributedLockMap.getLock(key) : null;
            boolean distLocked = false;
            if (distLock != null) {
                distLocked = tryLock ? distLock.tryLock() : acquireLockSafely(distLock);
                if (tryLock && !distLocked) {
                    log.warn("Distributed tryLock fast-fail, cacheName={}, key={}", getCacheName(), cacheKey);
                    throw new RedisTryLockFailException("Distributed tryLock fast-fail, key=" + cacheKey);
                }
            }

            try {
                // 三重检查
                value = unwrap(bucket.get());
                if (value == null) {
                    value = valueLoader.call();
                    suppressPublishIfNull(value, valueLoader);
                    put(key, value);
                }
                return fromCacheValue(value);
            } finally {
                if (distLocked && distLock.isHeldByCurrentThread()) {
                    distLock.unlock();
                }
            }
        } catch (RedisTryLockFailException e) {
            throw e;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load value with lock for key: " + key, ex);
        } finally {
            // 仅在确实持有本地锁时才释放
            if (localLocked && localLock.isHeldByCurrentThread()) {
                localLock.unlock();
            }
        }
    }

    private Object loadFirstTimeLogical(Object key, String cacheKey, RBucket<Object> bucket, Callable<?> valueLoader) {
        RLock lock = redissonClient.getLock(cacheKey + CacheConsts.SPLIT_SINGLE + "logical_load");
        boolean locked = false;
        try {
            locked = lock.tryLock(0, 10, TimeUnit.SECONDS);
            if (!locked) {
                return fromCacheValue(unwrap(bucket.get()));
            }
            Object existing = bucket.get();
            if (existing != null) {
                return fromCacheValue(unwrap(existing));
            }

            Object value = valueLoader.call();
            suppressPublishIfNull(value, valueLoader);
            put(key, value);
            return fromCacheValue(value);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("[RedissonRBucketCache] logical first-load interrupted, cacheName={}, key={}", getCacheName(), cacheKey);
            return null;
        } catch (Exception ex) {
            throw new RuntimeException("Failed to load value for logical expire first time, key: " + key, ex);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void triggerAsyncRefresh(Object key, String cacheKey, Callable<?> valueLoader) {
        if (valueLoader == null) {
            return;
        }
        LOGICAL_REFRESH_POOL.execute(() -> {
            RLock lock = redissonClient.getLock(cacheKey + CacheConsts.SPLIT_SINGLE + "logical_refresh");
            boolean locked = false;
            try {
                locked = lock.tryLock();
                if (!locked) {
                    return;
                }
                Object value = valueLoader.call();
                put(key, value);
                log.debug("[RedissonRBucketCache] logical-expire async refresh done, cacheName={}, key={}", getCacheName(), cacheKey);
            } catch (Exception e) {
                log.error("[RedissonRBucketCache] logical-expire async refresh failed, cacheName={}, key={}", getCacheName(), cacheKey, e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
    }

    // ---------- 写入实现 ----------

    private void doPut(Object key, Object value) {
        String cacheKey = buildKey(key);
        RBucket<Object> bucket = getBucket(cacheKey);

        if (!isAllowNullValues() && value == null) {
            boolean deleted = bucket.delete();
            log.warn("delete cache (null not allowed), cacheName={}, key={}, deleted={}", getCacheName(), cacheKey, deleted);
            return;
        }

        Object storeValue = toCacheValue(value);
        Map.Entry<Long, TimeUnit> expire = dealExpireTime(storeValue);
        setBucketWithExpire(bucket, storeValue, expire.getKey(), expire.getValue());
    }

    private Object doPutIfAbsent(Object key, Object value) {
        String cacheKey = buildKey(key);
        RBucket<Object> bucket = getBucket(cacheKey);
        Object oldRaw = bucket.get();

        Object storeValue = toCacheValue(value);
        Map.Entry<Long, TimeUnit> expire = dealExpireTime(storeValue);

        boolean setResult;
        if (isLogicalExpire()) {
            LogicalExpireWrapper wrapper = buildLogicalExpireWrapper(storeValue, expire.getKey(), expire.getValue());
            int factor = resolveLogicalExpirePhysicalTtlFactor();
            // factor = 0: no physical TTL
            // factor >= 1: physical TTL = logical TTL * factor
            if (factor == 0) {
                setResult = bucket.setIfAbsent(wrapper);
            } else {
                long logicalMs = expire.getValue().toMillis(expire.getKey());
                long physicalMs = logicalMs * factor;
                setResult = bucket.setIfAbsent(wrapper, Duration.ofMillis(physicalMs));
            }
        } else if (expire.getKey() == -1) {
            // TTL = -1: never expire (all strategies support this)
            setResult = bucket.setIfAbsent(storeValue);
        } else if (expire.getKey() > 0) {
            setResult = bucket.setIfAbsent(storeValue, Duration.ofMillis(expire.getValue().toMillis(expire.getKey())));
        } else {
            setResult = bucket.setIfAbsent(storeValue);
        }

        log.debug("putIfAbsent, cacheName={}, result={}, key={}", getCacheName(), setResult, cacheKey);
        return fromCacheValue(unwrap(oldRaw));
    }

    // ---------- 批量操作实现 ----------

    @SuppressWarnings("unchecked")
    private <K, V> Map<K, V> doBatchGet(Map<K, Object> keyMap, boolean returnNullValueKey) {
        if (keyMap == null || keyMap.isEmpty()) {
            log.debug("batchGet keyMap empty, cacheName={}", getCacheName());
            return Collections.emptyMap();
        }

        // ConcurrentHashMap：Netty 回调线程并发写入无锁竞争
        Map<K, V> hitMap = new ConcurrentHashMap<>();
        List<List<K>> partitions = Lists.partition(new ArrayList<>(keyMap.keySet()), redisConfig.getBatchSize());

        for (List<K> partition : partitions) {
            RBatch batch = redissonClient.createBatch();
            for (K key : partition) {
                String cacheKey = buildKey(keyMap.get(key));
                RFuture<Object> async = batch.getBucket(cacheKey).getAsync();
                async.whenComplete(new MdcBiConsumerWrapper<>((value, exception) -> {
                    if (exception != null) {
                        log.warn("batchGet error, cacheKey={}, msg={}", cacheKey, exception.getMessage());
                        return;
                    }
                    if (value == null) {
                        return;
                    }

                    Object actual = unwrap(value);
                    V wrapped = (V) fromCacheValue(actual);
                    if (wrapped != null) {
                        hitMap.put(key, wrapped);
                    } else if (returnNullValueKey) {
                        hitMap.put(key, null);
                        log.debug("[RedissonRBucketCache] batchGet NullValue hit, cacheKey={}", cacheKey);
                    }
                }));
            }
            batch.execute();
            log.debug("[RedissonRBucketCache] batchGet partition done, cacheName={}, partitionSize={}, hitMapSize={}",
                    getCacheName(), partition.size(), hitMap.size());
        }

        log.debug("[RedissonRBucketCache] batchGet done, cacheName={}, totalKeys={}, hitMapSize={}",
                getCacheName(), keyMap.size(), hitMap.size());
        return hitMap;
    }

    private <V> void doBatchPut(Map<Object, V> dataMap) {
        log.debug("batchPut start, cacheName={}, size={}", getCacheName(), dataMap.size());
        List<List<Object>> partitions = Lists.partition(new ArrayList<>(dataMap.keySet()), redisConfig.getBatchSize());

        for (List<Object> partition : partitions) {
            RBatch batch = redissonClient.createBatch();
            for (Object key : partition) {
                String cacheKey = buildKey(key);
                Object storeValue = toCacheValue(dataMap.get(key));
                Map.Entry<Long, TimeUnit> expire = dealExpireTime(storeValue);

                if (isLogicalExpire()) {
                    LogicalExpireWrapper wrapper = buildLogicalExpireWrapper(storeValue, expire.getKey(), expire.getValue());
                    int factor = resolveLogicalExpirePhysicalTtlFactor();
                    // factor = 0: no physical TTL
                    // factor >= 1: physical TTL = logical TTL * factor
                    if (factor == 0) {
                        batch.getBucket(cacheKey).setAsync(wrapper);
                    } else {
                        long logicalMs = expire.getValue().toMillis(expire.getKey());
                        long physicalMs = logicalMs * factor;
                        batch.getBucket(cacheKey).setAsync(wrapper, physicalMs, TimeUnit.MILLISECONDS);
                    }
                } else if (expire.getKey() == -1) {
                    // TTL = -1: never expire (all strategies support this)
                    batch.getBucket(cacheKey).setAsync(storeValue);
                } else if (expire.getKey() > 0) {
                    batch.getBucket(cacheKey).setAsync(storeValue, expire.getKey(), expire.getValue());
                } else {
                    batch.getBucket(cacheKey).setAsync(storeValue);
                }
            }
            batch.execute();
        }
        log.debug("batchPut done, cacheName={}, size={}", getCacheName(), dataMap.size());
    }

    private <K> void doBatchEvict(Map<K, Object> keyMap) {
        log.debug("batchEvict start, cacheName={}, size={}", getCacheName(), keyMap.size());
        List<List<Map.Entry<K, Object>>> partitions = Lists.partition(
                new ArrayList<>(keyMap.entrySet()), redisConfig.getBatchSize());

        for (List<Map.Entry<K, Object>> partition : partitions) {
            RBatch batch = redissonClient.createBatch();
            for (Map.Entry<K, Object> entry : partition) {
                String cacheKey = buildKey(entry.getValue());
                RFuture<Object> async = batch.getBucket(cacheKey).getAndDeleteAsync();
                async.whenComplete(new MdcBiConsumerWrapper<>((value, exception) -> {
                    if (exception != null) {
                        log.warn("batchEvict error, cacheKey={}, msg={}", cacheKey, exception.getMessage());
                    } else {
                        log.debug("batchEvict success, cacheKey={}", cacheKey);
                    }
                }));
            }
            batch.execute();
        }
        log.debug("batchEvict done, cacheName={}, size={}", getCacheName(), keyMap.size());
    }

    // =====================================================================
    //                       抽取的公共工具方法
    // =====================================================================

    /**
     * Unified write entry: eliminates duplicate logic in doPut / doBatchPut / putLogicalExpire
     */
    private void setBucketWithExpire(RBucket<Object> bucket, Object value, long expireTime, TimeUnit timeUnit) {
        if (isLogicalExpire()) {
            LogicalExpireWrapper wrapper = buildLogicalExpireWrapper(value, expireTime, timeUnit);
            int factor = resolveLogicalExpirePhysicalTtlFactor();
            // If factor = 0, no physical TTL (never expire)
            if (factor == 0) {
                bucket.set(wrapper);
                log.debug("put logical-expire (no physical ttl), cacheName={}, logicalExpireAt={}, key={}",
                        getCacheName(), wrapper.getLogicalExpireAt(), bucket.getName());
            } else {
                // Set physical TTL = logical TTL * factor, as a fallback protection
                long logicalMs = timeUnit.toMillis(expireTime);
                long physicalMs = logicalMs * factor;
                bucket.set(wrapper, Duration.ofMillis(physicalMs));
                log.debug("put logical-expire, cacheName={}, logicalExpireAt={}, physicalMs={}, key={}",
                        getCacheName(), wrapper.getLogicalExpireAt(), physicalMs, bucket.getName());
            }
        } else if (expireTime == -1) {
            // TTL = -1: never expire (all strategies support this)
            bucket.set(value);
            log.debug("put (never expire, TTL=-1), cacheName={}, key={}", getCacheName(), bucket.getName());
        } else if (expireTime > 0) {
            bucket.set(value, Duration.ofMillis(timeUnit.toMillis(expireTime)));
            log.debug("put, cacheName={}, expire={} {}, key={}", getCacheName(), expireTime, timeUnit, bucket.getName());
        } else {
            bucket.set(value);
            log.debug("put (no ttl), cacheName={}, key={}", getCacheName(), bucket.getName());
        }
    }

    /** 统一构建逻辑过期包装器 */
    private LogicalExpireWrapper buildLogicalExpireWrapper(Object value, long expireTime, TimeUnit timeUnit) {
        // 逻辑过期策略必须配置正数过期时间
        if (expireTime <= 0) {
            throw new IllegalStateException(
                "Logical expire strategy requires positive expireTime, but got: " + expireTime +
                ". Please configure a valid expire time for this cache.");
        }
        long logicalMs = timeUnit.toMillis(expireTime);
        return new LogicalExpireWrapper(value, System.currentTimeMillis() + logicalMs);
    }

    /** 统一判断 valueLoader 是否有效（消除 4 处嵌套 instanceof） */
    private boolean hasEffectiveLoader(Callable<?> valueLoader) {
        if (!(valueLoader instanceof PublishMessageTask)) {
            return true;
        }
        PublishMessageTask pmt = (PublishMessageTask) valueLoader;
        Callable<?> inner = pmt.getValueLoader();
        if (inner == null) {
            return false;
        }
        if (inner instanceof LoadValueTask) {
            return ((LoadValueTask) inner).getValueLoader() != null;
        }
        return true;
    }

    /** 统一处理 null 值时抑制消息发布 */
    private void suppressPublishIfNull(Object value, Callable<?> valueLoader) {
        if (value == null && valueLoader instanceof PublishMessageTask) {
            ((PublishMessageTask) valueLoader).setPublishMsg(false);
            log.warn("Loaded null from source, suppress publish, cacheName={}", getCacheName());
        }
    }

    /** 安全获取阻塞锁：避免 lock() 抛异常后 finally 中 unlock 非法监视器 */
    private boolean acquireLockSafely(ReentrantLock lock) {
        lock.lock();
        return true;
    }

    /** 安全获取 RLock 阻塞锁 */
    private boolean acquireLockSafely(RLock lock) {
        lock.lock();
        return true;
    }

    // ---------- L2 降级辅助 ----------

    private <T> T l2Read(Supplier<T> redisCall, T fallback) {
        if (!degradeEnabled) {
            return redisCall.get();
        }
        if (!breaker.allowRequest()) {
            metricsRecorder.recordException(getCacheName(), "l2-degraded");
            return fallback;
        }
        try {
            T result = redisCall.get();
            breaker.onSuccess();
            return result;
        } catch (RedisException e) {
            breaker.onFailure();
            metricsRecorder.recordException(getCacheName(), "l2-degraded");
            log.warn("[L2 degrade] redis read error, cacheName={}", getCacheName(), e);
            return fallback;
        } catch (RuntimeException e) {
            breaker.onSuccess(); // 非连接异常：释放 HALF_OPEN 令牌
            throw e;
        }
    }

    private void l2Write(String op, Runnable redisCall) {
        if (!degradeEnabled) {
            redisCall.run();
            return;
        }
        if (!breaker.allowRequest()) {
            metricsRecorder.recordException(getCacheName(), "l2-degraded");
            log.debug("[L2 degrade] circuit open, skip {}, cacheName={}", op, getCacheName());
            return;
        }
        try {
            redisCall.run();
            breaker.onSuccess();
        } catch (RedisException e) {
            breaker.onFailure();
            metricsRecorder.recordException(getCacheName(), "l2-degraded");
            log.warn("[L2 degrade] redis write error, skip {}, cacheName={}", op, getCacheName(), e);
        } catch (RuntimeException e) {
            breaker.onSuccess();
            throw e;
        }
    }

    private Object degraded(Object key, Callable<?> valueLoader) {
        metricsRecorder.recordException(getCacheName(), "l2-degraded");
        if (valueLoader == null) {
            return null;
        }

        boolean acquired = false;
        if (degradeSemaphore != null) {
            acquired = degradeSemaphore.tryAcquire();
            if (!acquired) {
                log.warn("[L2 degrade] load shed, cacheName={}, key={}", getCacheName(), key);
                return null;
            }
        }
        try {
            Object value = valueLoader.call();
            suppressPublishIfNull(value, valueLoader);
            return fromCacheValue(value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load value during degrade, key: " + key, e);
        } finally {
            if (acquired) {
                degradeSemaphore.release();
            }
        }
    }

    private static boolean containsRedisError(Throwable t) {
        int depth = 0;
        while (t != null && depth++ < 16) {
            if (t instanceof RedisException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private Map.Entry<Long, TimeUnit> dealExpireTime(Object value) {
        if (value instanceof NullValueWrapper) {
            long seconds = Math.max(0, getNullValueExpireTimeSeconds());
            seconds = RandomUtil.jitter(seconds, redisConfig.getTtlJitterRatio());
            return new AbstractMap.SimpleEntry<>(seconds, TimeUnit.SECONDS);
        }
        long expire = getExpireTime();
        // TTL = -1 means never expire (no jitter for -1)
        if (expire == -1) {
            return new AbstractMap.SimpleEntry<>(-1L, getExpireTimeUnit());
        }
        // For positive TTL, apply jitter
        expire = Math.max(0, expire);
        expire = RandomUtil.jitter(expire, redisConfig.getTtlJitterRatio());
        return new AbstractMap.SimpleEntry<>(expire, getExpireTimeUnit());
    }
}