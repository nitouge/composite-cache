package io.github.nitouge.cache.core.validation;

import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L1CacheSetting;
import io.github.nitouge.cache.core.config.setting.L2CacheSetting;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import io.github.nitouge.cache.core.consts.enums.CacheMsgTypeEnum;
import io.github.nitouge.cache.core.consts.enums.CacheTypeEnum;
import io.github.nitouge.cache.core.exception.CacheConfigException;
import io.github.nitouge.cache.core.exception.factory.CacheExceptionFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * 缓存配置验证器
 * 
 * <p>负责验证缓存配置的合法性，确保配置参数符合要求。
 * 
 * <h3>验证项</h3>
 * <ul>
 *   <li>基础配置验证：instanceId、cacheMode等</li>
 *   <li>缓存模式验证：L1、L2、L1_L2模式的配置</li>
 *   <li>缓存类型验证：Caffeine、Guava、Redis等</li>
 *   <li>同步策略验证：消息类型、topic等</li>
 *   <li>数值范围验证：过期时间、批量大小等</li>
 * </ul>
 * 
 */
@Slf4j
public class CacheConfigValidator {

    /**
     * 验证缓存配置
     * 
     * @param config 缓存配置
     * @throws CacheConfigException 配置不合法时抛出
     */
    public static void validate(CacheConfig config) {
        if (config == null) {
            throw CacheExceptionFactory.configError("config", null, "Cache Config cannot be null");
        }

        log.info("Start validating cache config...");

        // 1. 验证基础配置
        validateBasicConfig(config);

        // 2. 验证缓存模式（含各模式下 L1/L2 缓存类型非空、类型支持与具体配置的校验）
        validateCacheMode(config);

        // 3. 验证同步策略
        validateSyncPolicy(config);

        // 4. 验证数值范围
        validateNumericValues(config);

        // 5. 验证防穿透（布隆）配置
        validatePenetrationConfig(config);

        log.info("Cache config validation passed successfully");
    }

    /**
     * 验证基础配置
     */
    private static void validateBasicConfig(CacheConfig config) {
        // 验证instanceId
        if (config.getInstanceId() == null || config.getInstanceId().trim().isEmpty()) {
            throw CacheExceptionFactory.configError("instanceId", config.getInstanceId(), 
                "Instance ID cannot be blank");
        }

        // 验证cacheMode
        if (config.getCacheMode() == null) {
            throw CacheExceptionFactory.configError("cacheMode", null, 
                "Cache mode cannot be null");
        }

        log.debug("Basic config validation passed: instanceId={}, cacheMode={}", 
            config.getInstanceId(), config.getCacheMode());
    }

    /**
     * 验证缓存模式
     */
    private static void validateCacheMode(CacheConfig config) {
        // cacheMode 非空已在 validateBasicConfig 校验，此处直接按模式分派
        CacheModeEnum cacheModeEnum = config.getCacheMode();

        // 根据不同模式验证对应配置（含 L1/L2 缓存类型非空、类型支持与各自具体配置）
        switch (cacheModeEnum) {
            case L1:
                validateL1Config(config);
                break;
            case L2:
                validateL2Config(config);
                break;
            case L1_L2:
                validateL1Config(config);
                validateL2Config(config);
                break;
            default:
                throw new CacheConfigException(String.format("Unsupported cache mode: %s", cacheModeEnum));
        }

        log.debug("Cache mode validation passed: mode={}", cacheModeEnum);
    }

    /**
     * 验证L1缓存配置
     */
    private static void validateL1Config(CacheConfig config) {
        CacheTypeEnum l1CacheType = config.getComposite().getL1CacheType();
        
        if (l1CacheType == null) {
            throw CacheExceptionFactory.configError("composite.l1CacheType", null, 
                "L1 cache type cannot be null when cache mode requires L1");
        }

        // 验证L1缓存类型是否支持
        if (l1CacheType != CacheTypeEnum.CAFFEINE && l1CacheType != CacheTypeEnum.GUAVA) {
            throw CacheExceptionFactory.configError("composite.l1CacheType", l1CacheType, 
                String.format("L1 cache only supports CAFFEINE or GUAVA, but got: %s", l1CacheType));
        }

        // 验证具体的L1缓存配置
        if (l1CacheType == CacheTypeEnum.CAFFEINE) {
            validateCaffeineConfig(config.getCaffeine());
        } else if (l1CacheType == CacheTypeEnum.GUAVA) {
            validateGuavaConfig(config.getGuava());
        }

        log.debug("L1 cache config validation passed: type={}", l1CacheType);
    }

    /**
     * 验证L2缓存配置
     */
    private static void validateL2Config(CacheConfig config) {
        CacheTypeEnum l2CacheType = config.getComposite().getL2CacheType();
        
        if (l2CacheType == null) {
            throw CacheExceptionFactory.configError("composite.l2CacheType", null, 
                "L2 cache type cannot be null when cache mode requires L2");
        }

        // 验证L2缓存类型是否支持
        if (l2CacheType != CacheTypeEnum.REDIS) {
            throw CacheExceptionFactory.configError("composite.l2CacheType", l2CacheType, 
                String.format("L2 cache only supports REDIS, but got: %s", l2CacheType));
        }

        // 验证Redis配置
        validateRedisConfig(config.getRedis());

        log.debug("L2 cache config validation passed: type={}", l2CacheType);
    }

    /**
     * 验证Caffeine配置
     */
    private static void validateCaffeineConfig(CacheConfig.CaffeineConfig config) {
        if (config == null) {
            throw CacheExceptionFactory.configError("caffeine", null, 
                "Caffeine config cannot be null when using Caffeine cache");
        }

        // 验证刷新线程池大小
        if (config.getRefreshThreadPoolSize() != null && config.getRefreshThreadPoolSize() <= 0) {
            throw CacheExceptionFactory.configError("caffeine.refreshThreadPoolSize", 
                config.getRefreshThreadPoolSize(), 
                "Refresh thread pool size must be greater than 0");
        }

        // 验证刷新周期
        if (config.getRefreshPeriod() != null && config.getRefreshPeriod() <= 0) {
            throw CacheExceptionFactory.configError("caffeine.refreshPeriod", 
                config.getRefreshPeriod(), 
                "Refresh period must be greater than 0");
        }

        // 验证发布消息频率
        if (config.getPublishMsgPeriodMilliSeconds() != null && config.getPublishMsgPeriodMilliSeconds() <= 0) {
            throw CacheExceptionFactory.configError("caffeine.publishMsgPeriodMilliSeconds", 
                config.getPublishMsgPeriodMilliSeconds(), 
                "Publish message period must be greater than 0");
        }

        log.debug("Caffeine config validation passed");
    }

    /**
     * 验证Guava配置
     */
    private static void validateGuavaConfig(CacheConfig.GuavaConfig config) {
        if (config == null) {
            throw CacheExceptionFactory.configError("guava", null, 
                "Guava config cannot be null when using Guava cache");
        }

        // 验证刷新线程池大小
        if (config.getRefreshThreadPoolSize() != null && config.getRefreshThreadPoolSize() <= 0) {
            throw CacheExceptionFactory.configError("guava.refreshThreadPoolSize", 
                config.getRefreshThreadPoolSize(), 
                "Refresh thread pool size must be greater than 0");
        }

        // 验证刷新周期
        if (config.getRefreshPeriod() != null && config.getRefreshPeriod() <= 0) {
            throw CacheExceptionFactory.configError("guava.refreshPeriod", 
                config.getRefreshPeriod(), 
                "Refresh period must be greater than 0");
        }

        log.debug("Guava config validation passed");
    }

    /**
     * 验证Redis配置
     */
    private static void validateRedisConfig(CacheConfig.RedisConfig config) {
        if (config == null) {
            throw CacheExceptionFactory.configError("redis", null, 
                "Redis config cannot be null when using Redis cache");
        }

        // 验证批量操作大小（batchSize是int基本类型，不需要判断null）
        if (config.getBatchSize() <= 0) {
            throw CacheExceptionFactory.configError("redis.batchSize", 
                config.getBatchSize(), 
                "Batch size must be greater than 0");
        }

        // 验证批量操作大小不能太大
        if (config.getBatchSize() > 1000) {
            throw CacheExceptionFactory.configError("redis.batchSize",
                config.getBatchSize(),
                "Batch size should not exceed 1000 to avoid performance issues");
        }

        // 验证逻辑过期物理 TTL 放大倍数（>=1，保证逻辑过期后旧值仍在）
        if (config.getLogicalExpirePhysicalTtlFactor() < 1) {
            throw CacheExceptionFactory.configError("redis.logicalExpirePhysicalTtlFactor",
                config.getLogicalExpirePhysicalTtlFactor(),
                "logicalExpirePhysicalTtlFactor must be >= 1");
        }

        // 验证 TTL 抖动比例（>=0，0 表示关闭；防雪崩）
        if (config.getTtlJitterRatio() < 0) {
            throw CacheExceptionFactory.configError("redis.ttlJitterRatio",
                config.getTtlJitterRatio(),
                "ttlJitterRatio cannot be negative (0 disables jitter)");
        }

        // 验证降级熔断参数（仅在 degradeEnabled 时实际生效，但配置值范围始终校验）
        if (config.getDegradeFailureThreshold() <= 0) {
            throw CacheExceptionFactory.configError("redis.degradeFailureThreshold",
                config.getDegradeFailureThreshold(),
                "degradeFailureThreshold must be greater than 0");
        }
        if (config.getDegradeOpenMillis() <= 0) {
            throw CacheExceptionFactory.configError("redis.degradeOpenMillis",
                config.getDegradeOpenMillis(),
                "degradeOpenMillis must be greater than 0");
        }
        if (config.getDegradeMaxConcurrentLoads() < 0) {
            throw CacheExceptionFactory.configError("redis.degradeMaxConcurrentLoads",
                config.getDegradeMaxConcurrentLoads(),
                "degradeMaxConcurrentLoads cannot be negative (0 means unlimited)");
        }

        log.debug("Redis config validation passed");
    }

    /**
     * 验证防穿透（布隆过滤器）配置
     */
    private static void validatePenetrationConfig(CacheConfig config) {
        CacheConfig.PenetrationConfig penetration = config.getPenetration();
        if (penetration == null) {
            return;
        }

        if (penetration.getBloomExpectedInsertions() <= 0) {
            throw CacheExceptionFactory.configError("penetration.bloomExpectedInsertions",
                penetration.getBloomExpectedInsertions(),
                "bloomExpectedInsertions must be greater than 0");
        }

        double fpp = penetration.getBloomFpp();
        if (fpp <= 0 || fpp >= 1) {
            throw CacheExceptionFactory.configError("penetration.bloomFpp",
                fpp,
                "bloomFpp must be in (0, 1)");
        }

        log.debug("Penetration config validation passed");
    }

    /**
     * 验证同步策略配置
     */
    private static void validateSyncPolicy(CacheConfig config) {
        CacheConfig.CacheSyncPolicyConfig syncConfig = config.getCacheSyncPolicy();
        
        if (syncConfig == null) {
            log.debug("Sync policy config is null, skip validation");
            return;
        }

        CacheMsgTypeEnum msgType = syncConfig.getMsgType();
        
        // 如果配置了消息类型，则验证
        if (msgType != null) {
            // 验证topic
            if (syncConfig.getTopic() == null || syncConfig.getTopic().trim().isEmpty()) {
                throw CacheExceptionFactory.configError("cacheSyncPolicy.topic", 
                    syncConfig.getTopic(), 
                    "Topic cannot be blank when sync policy is enabled");
            }

            log.debug("Sync policy validation passed: msgType={}, topic={}", 
                msgType, syncConfig.getTopic());
        }
    }

    /**
     * 验证数值范围
     */
    private static void validateNumericValues(CacheConfig config) {
        // 验证NullValue过期时间
        if (config.getNullValueExpireTimeSeconds() < 0) {
            throw CacheExceptionFactory.configError("nullValueExpireTimeSeconds", 
                config.getNullValueExpireTimeSeconds(), 
                "Null value expire time cannot be negative");
        }

        // 验证NullValue最大数量
        if (config.getNullValueMaxSize() <= 0) {
            throw CacheExceptionFactory.configError("nullValueMaxSize", 
                config.getNullValueMaxSize(), 
                "Null value max size must be greater than 0");
        }

        // 验证NullValue清理周期
        if (config.getNullValueClearPeriodSeconds() <= 0) {
            throw CacheExceptionFactory.configError("nullValueClearPeriodSeconds", 
                config.getNullValueClearPeriodSeconds(), 
                "Null value clear period must be greater than 0");
        }

        log.debug("Numeric values validation passed");
    }

    /**
     * 验证缓存设置
     * 
     * @param cacheName 缓存名称
     * @param cacheSetting 缓存设置
     * @throws CacheConfigException 配置不合法时抛出
     */
    public static void validateCacheSetting(String cacheName, CacheSetting cacheSetting) {
        if (cacheName == null || cacheName.trim().isEmpty()) {
            throw CacheExceptionFactory.configError("cacheName", cacheName, 
                "Cache name cannot be blank");
        }

        if (cacheSetting == null) {
            throw CacheExceptionFactory.configError("cacheSetting", null, 
                String.format("Cache setting cannot be null for cache [%s]", cacheName));
        }

        // 验证L1缓存设置
        if (cacheSetting.getL1CacheSetting() != null) {
            validateL1CacheSetting(cacheName, cacheSetting.getL1CacheSetting());
        }

        // 验证L2缓存设置
        if (cacheSetting.getL2CacheSetting() != null) {
            validateL2CacheSetting(cacheName, cacheSetting.getL2CacheSetting());
        }

        log.debug("Cache setting validation passed: cacheName={}", cacheName);
    }

    /**
     * 验证L1缓存设置
     */
    private static void validateL1CacheSetting(String cacheName, L1CacheSetting setting) {
        // 验证初始容量
        if (setting.getInitialCapacity() < 0) {
            throw CacheExceptionFactory.configError("l1CacheSetting.initialCapacity", 
                setting.getInitialCapacity(), 
                String.format("Initial capacity cannot be negative for cache [%s]", cacheName));
        }

        // 验证最大容量
        if (setting.getMaximumSize() <= 0) {
            throw CacheExceptionFactory.configError("l1CacheSetting.maximumSize", 
                setting.getMaximumSize(), 
                String.format("Maximum size must be greater than 0 for cache [%s]", cacheName));
        }

        // 验证过期时间
        if (setting.getExpireTime() < 0) {
            throw CacheExceptionFactory.configError("l1CacheSetting.expireTime", 
                setting.getExpireTime(), 
                String.format("Expire time cannot be negative for cache [%s]", cacheName));
        }
        
        // 验证时间单位
        if (setting.getExpireTimeUnit() == null) {
            throw CacheExceptionFactory.configError("l1CacheSetting.expireTimeUnit", null, 
                String.format("Expire time unit cannot be null for cache [%s]", cacheName));
        }

        log.debug("L1 cache setting validation passed: cacheName={}", cacheName);
    }

    /**
     * 验证L2缓存设置
     */
    private static void validateL2CacheSetting(String cacheName, L2CacheSetting setting) {
        // 验证过期时间（-1表示永不过期，所以只检查是否小于-1）
        if (setting.getExpireTime() < -1) {
            throw CacheExceptionFactory.configError("l2CacheSetting.expireTime", 
                setting.getExpireTime(), 
                String.format("Expire time cannot be less than -1 for cache [%s] (use -1 for no expiration)", cacheName));
        }

        // 验证时间单位
        if (setting.getExpireTimeUnit() == null) {
            throw CacheExceptionFactory.configError("l2CacheSetting.expireTimeUnit", null, 
                String.format("Expire time unit cannot be null for cache [%s]", cacheName));
        }

        log.debug("L2 cache setting validation passed: cacheName={}", cacheName);
    }
}
