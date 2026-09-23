package io.github.nitouge.cache.test.controller;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;
import io.github.nitouge.cache.core.impl.CompositeCache;
import io.github.nitouge.cache.core.impl.level2.RedissonRBucketCache;
import io.github.nitouge.cache.core.wrapper.LogicalExpireWrapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/test/cache")
public class CacheDebugController {

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 测试：手动写入 Redis 验证 LOGICAL_EXPIRE 策略
     */
    @PostMapping("/debug/put")
    public Map<String, Object> debugPut(@RequestParam String cacheName,
                                         @RequestParam String key,
                                         @RequestParam String value) {
        Map<String, Object> result = new HashMap<>();

        try {
            // 1. 获取缓存实例
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                result.put("error", "Cache not found: " + cacheName);
                return result;
            }

            log.info("=== Debug PUT Start ===");
            log.info("cacheName={}, key={}, value={}", cacheName, key, value);
            log.info("cache type={}", cache.getClass().getName());

            // 2. 执行 put 操作
            cache.put(key, value);
            log.info("cache.put() executed");

            // 3. 立即从缓存读取
            Object cached = cache.get(key, String.class);
            log.info("cached value from cache.get()={}", cached);
            result.put("cachedValue", cached);

            // 4. 直接从 Redis 读取验证
            if (cache instanceof CompositeCache) {
                CompositeCache cc = (CompositeCache) cache;
                L2Cache l2 = cc.getL2Cache();
                if (l2 instanceof RedissonRBucketCache) {
                    RedissonRBucketCache redisCache = (RedissonRBucketCache) l2;
                    String redisKey = redisCache.buildKey(key);

                    RBucket<Object> bucket = redissonClient.getBucket(redisKey);
                    Object redisRaw = bucket.get();

                    log.info("Redis key={}", redisKey);
                    log.info("Redis raw value={}", redisRaw);
                    log.info("Redis raw value type={}", redisRaw != null ? redisRaw.getClass().getName() : "null");

                    result.put("redisKey", redisKey);
                    result.put("redisRawValue", String.valueOf(redisRaw));
                    result.put("redisExists", bucket.isExists());

                    // 如果是逻辑过期包装器，解析它
                    if (redisRaw instanceof LogicalExpireWrapper) {
                        LogicalExpireWrapper wrapper = (LogicalExpireWrapper) redisRaw;
                        result.put("isLogicalExpireWrapper", true);
                        result.put("logicalExpireAt", wrapper.getLogicalExpireAt());
                        result.put("wrappedData", wrapper.getData());
                        result.put("isExpired", wrapper.isExpired());
                        log.info("LogicalExpireWrapper: data={}, expireAt={}, isExpired={}",
                                wrapper.getData(), wrapper.getLogicalExpireAt(), wrapper.isExpired());
                    } else {
                        result.put("isLogicalExpireWrapper", false);
                    }
                }
            }

            log.info("=== Debug PUT End ===");
            result.put("success", true);

        } catch (Exception e) {
            log.error("Debug put failed", e);
            result.put("error", e.getMessage());
            result.put("exception", e.getClass().getName());
        }

        return result;
    }

    /**
     * 测试：检查 Redis 中的所有 key
     */
    @GetMapping("/debug/keys")
    public Map<String, Object> debugKeys(@RequestParam(required = false) String pattern) {
        Map<String, Object> result = new HashMap<>();

        try {
            if (pattern == null || pattern.isEmpty()) {
                pattern = "*";
            }

            RKeys keys = redissonClient.getKeys();
            Iterable<String> keyIterable = keys.getKeysByPattern(pattern, 100);

            List<Map<String, Object>> keyInfoList = new ArrayList<>();
            for (String key : keyIterable) {
                Map<String, Object> keyInfo = new HashMap<>();
                keyInfo.put("key", key);

                RBucket<Object> bucket = redissonClient.getBucket(key);
                Object value = bucket.get();

                keyInfo.put("exists", bucket.isExists());
                keyInfo.put("valueType", value != null ? value.getClass().getSimpleName() : "null");

                if (value instanceof LogicalExpireWrapper) {
                    LogicalExpireWrapper wrapper = (LogicalExpireWrapper) value;
                    keyInfo.put("isLogicalExpireWrapper", true);
                    keyInfo.put("isExpired", wrapper.isExpired());
                    keyInfo.put("wrappedDataType", wrapper.getData() != null ? wrapper.getData().getClass().getSimpleName() : "null");
                } else {
                    keyInfo.put("isLogicalExpireWrapper", false);
                    keyInfo.put("value", String.valueOf(value));
                }

                keyInfoList.add(keyInfo);
            }

            result.put("pattern", pattern);
            result.put("count", keyInfoList.size());
            result.put("keys", keyInfoList);
            result.put("success", true);

        } catch (Exception e) {
            log.error("Debug keys failed", e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 测试：检查缓存配置
     */
    @GetMapping("/debug/config")
    public Map<String, Object> debugConfig(@RequestParam String cacheName) {
        Map<String, Object> result = new HashMap<>();

        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                result.put("error", "Cache not found: " + cacheName);
                return result;
            }

            result.put("cacheType", cache.getClass().getSimpleName());

            if (cache instanceof CompositeCache) {
                CompositeCache cc = (CompositeCache) cache;
                L2Cache l2 = cc.getL2Cache();

                if (l2 instanceof RedissonRBucketCache) {
                    RedissonRBucketCache redisCache = (RedissonRBucketCache) l2;

                    // 使用反射获取配置（因为字段是 private）
                    try {
                        Field redisConfigField = RedissonRBucketCache.class.getDeclaredField("redisConfig");
                        redisConfigField.setAccessible(true);
                        CacheConfig.RedisConfig redisConfig = (CacheConfig.RedisConfig) redisConfigField.get(redisCache);

                        result.put("loadStrategy", redisConfig.getEffectiveLoadStrategy().name());
                        result.put("degradeEnabled", redisConfig.isDegradeEnabled());
                        result.put("userPrefix", redisConfig.isUserPrefix());
                        result.put("logicalExpirePhysicalTtlFactor", redisConfig.getLogicalExpirePhysicalTtlFactor());
                        result.put("ttlJitterRatio", redisConfig.getTtlJitterRatio());

                        Field loadStrategyField = RedissonRBucketCache.class.getDeclaredField("loadStrategy");
                        loadStrategyField.setAccessible(true);
                        RedisLoadStrategyEnum loadStrategy = (RedisLoadStrategyEnum) loadStrategyField.get(redisCache);
                        result.put("actualLoadStrategy", loadStrategy.name());

                    } catch (Exception e) {
                        result.put("configError", "Cannot access config: " + e.getMessage());
                    }

                    result.put("l2CacheType", "RedissonRBucketCache");
                    result.put("expireTime", redisCache.getExpireTime());
                    result.put("expireTimeUnit", redisCache.getExpireTimeUnit().name());
                }

                result.put("l1CacheType", cc.getL1Cache().getClass().getSimpleName());
            }

            result.put("success", true);

        } catch (Exception e) {
            log.error("Debug config failed", e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 测试：检查熔断器状态
     */
    @GetMapping("/debug/circuit-breaker")
    public Map<String, Object> debugCircuitBreaker(@RequestParam String cacheName) {
        Map<String, Object> result = new HashMap<>();

        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                result.put("error", "Cache not found: " + cacheName);
                return result;
            }

            if (cache instanceof CompositeCache) {
                CompositeCache cc = (CompositeCache) cache;
                L2Cache l2 = cc.getL2Cache();

                if (l2 instanceof RedissonRBucketCache) {
                    RedissonRBucketCache redisCache = (RedissonRBucketCache) l2;

                    try {
                        // 获取熔断器状态
                        Field breakerField = RedissonRBucketCache.class.getDeclaredField("breaker");
                        breakerField.setAccessible(true);
                        Object breaker = breakerField.get(redisCache);

                        if (breaker != null) {
                            // 获取熔断器的状态信息
                            Field stateField = breaker.getClass().getDeclaredField("state");
                            stateField.setAccessible(true);
                            Object state = stateField.get(breaker);

                            Field failureCountField = breaker.getClass().getDeclaredField("failureCount");
                            failureCountField.setAccessible(true);
                            int failureCount = failureCountField.getInt(breaker);

                            Field lastFailureTimeField = breaker.getClass().getDeclaredField("lastFailureTime");
                            lastFailureTimeField.setAccessible(true);
                            long lastFailureTime = lastFailureTimeField.getLong(breaker);

                            result.put("breakerState", state.toString());
                            result.put("failureCount", failureCount);
                            result.put("lastFailureTime", lastFailureTime);
                            result.put("lastFailureTimeReadable", lastFailureTime > 0 ?
                                new java.util.Date(lastFailureTime).toString() : "Never");
                        } else {
                            result.put("breaker", "null (degrade disabled)");
                        }

                        // 获取降级配置
                        Field degradeEnabledField = RedissonRBucketCache.class.getDeclaredField("degradeEnabled");
                        degradeEnabledField.setAccessible(true);
                        boolean degradeEnabled = degradeEnabledField.getBoolean(redisCache);

                        result.put("degradeEnabled", degradeEnabled);

                    } catch (NoSuchFieldException e) {
                        result.put("error", "Cannot access breaker field: " + e.getMessage());
                    }
                }
            }

            result.put("success", true);

        } catch (Exception e) {
            log.error("Debug circuit breaker failed", e);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * 测试：检查 Redis 连接和写入能力
     */
    @GetMapping("/debug/redis-write-test")
    public Map<String, Object> debugRedisWriteTest() {
        Map<String, Object> result = new HashMap<>();

        try {
            // 测试直接写入 Redis
            String testKey = "test:write:" + System.currentTimeMillis();
            String testValue = "test-value";

            RBucket<String> bucket = redissonClient.getBucket(testKey);
            bucket.set(testValue, 60, java.util.concurrent.TimeUnit.SECONDS);

            String readValue = bucket.get();
            boolean writeSuccess = testValue.equals(readValue);

            result.put("redisWriteSuccess", writeSuccess);
            result.put("testKey", testKey);

            // 清理测试数据
            bucket.delete();

            // 检查缓存配置
            Cache cache = cacheManager.getCache("user");
            if (cache instanceof CompositeCache) {
                CompositeCache cc = (CompositeCache) cache;
                result.put("cacheMode", cc.getCacheType());
                result.put("hasL1", cc.getL1Cache() != null);
                result.put("hasL2", cc.getL2Cache() != null);
            }

            result.put("success", true);

        } catch (Exception e) {
            log.error("Redis write test failed", e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("errorType", e.getClass().getSimpleName());
        }

        return result;
    }
}
