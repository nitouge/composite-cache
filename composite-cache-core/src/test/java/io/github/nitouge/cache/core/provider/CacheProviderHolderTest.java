package io.github.nitouge.cache.core.provider;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L1CacheSetting;
import io.github.nitouge.cache.core.config.setting.L2CacheSetting;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import io.github.nitouge.cache.core.consts.enums.CacheTypeEnum;
import io.github.nitouge.cache.core.impl.CompositeCache;
import io.github.nitouge.cache.core.impl.level1.CaffeineCache;
import io.github.nitouge.cache.core.impl.level2.RedissonRBucketCache;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Test cache mode conflict resolution
 */
class CacheProviderHolderTest {

    @Test
    void testCacheModeConflict_L1_L2_Annotation_With_L2_Only_Global() {
        // Global config: L2 only
        CacheConfig config = createConfig(CacheModeEnum.L2);
        RedissonClient redissonClient = mock(RedissonClient.class);
        CacheProviderHolder holder = CacheProviderHolder.init(config, redissonClient);

        // Annotation config: L1_L2
        CacheSetting setting = createCacheSetting(CacheModeEnum.L1_L2);

        // Should degrade to L2 only (L1 provider not available)
        Cache cache = holder.getCache("testCache", setting);

        assertNotNull(cache);
        assertTrue(cache instanceof RedissonRBucketCache,
            "Should degrade to L2 when annotation requests L1_L2 but only L2 provider initialized");
    }

    @Test
    void testCacheModeConflict_L1_L2_Annotation_With_L1_Only_Global() {
        // Global config: L1 only
        CacheConfig config = createConfig(CacheModeEnum.L1);
        CacheProviderHolder holder = CacheProviderHolder.init(config, null);

        // Annotation config: L1_L2
        CacheSetting setting = createCacheSetting(CacheModeEnum.L1_L2);

        // Should degrade to L1 only (L2 provider not available)
        Cache cache = holder.getCache("testCache", setting);

        assertNotNull(cache);
        assertTrue(cache instanceof CaffeineCache,
            "Should degrade to L1 when annotation requests L1_L2 but only L1 provider initialized");
    }

    @Test
    void testCacheModeConflict_L1_Annotation_With_L2_Only_Global() {
        // Global config: L2 only
        CacheConfig config = createConfig(CacheModeEnum.L2);
        RedissonClient redissonClient = mock(RedissonClient.class);
        CacheProviderHolder holder = CacheProviderHolder.init(config, redissonClient);

        // Annotation config: L1
        CacheSetting setting = createCacheSetting(CacheModeEnum.L1);

        // Should degrade to L2 (L1 provider not available)
        Cache cache = holder.getCache("testCache", setting);

        assertNotNull(cache);
        assertTrue(cache instanceof RedissonRBucketCache,
            "Should degrade to L2 when annotation requests L1 but only L2 provider initialized");
    }

    @Test
    void testCacheModeConflict_L2_Annotation_With_L1_Only_Global() {
        // Global config: L1 only
        CacheConfig config = createConfig(CacheModeEnum.L1);
        CacheProviderHolder holder = CacheProviderHolder.init(config, null);

        // Annotation config: L2
        CacheSetting setting = createCacheSetting(CacheModeEnum.L2);

        // Should degrade to L1 (L2 provider not available)
        Cache cache = holder.getCache("testCache", setting);

        assertNotNull(cache);
        assertTrue(cache instanceof CaffeineCache,
            "Should degrade to L1 when annotation requests L2 but only L1 provider initialized");
    }

    @Test
    void testNoCacheModeConflict_L1_L2_Both() {
        // Global config: L1_L2
        CacheConfig config = createConfig(CacheModeEnum.L1_L2);
        RedissonClient redissonClient = mock(RedissonClient.class);
        CacheProviderHolder holder = CacheProviderHolder.init(config, redissonClient);

        // Annotation config: L1_L2
        CacheSetting setting = createCacheSetting(CacheModeEnum.L1_L2);

        // Should create composite cache normally
        Cache cache = holder.getCache("testCache", setting);

        assertNotNull(cache);
        assertTrue(cache instanceof CompositeCache,
            "Should create CompositeCache when both providers available");
    }

    private CacheConfig createConfig(CacheModeEnum cacheMode) {
        CacheConfig config = new CacheConfig();
        config.setInstanceId("test-instance");
        config.setCacheMode(cacheMode);

        // 直接使用 getter 获取 final 配置对象并设置属性
        config.getComposite()
            .setL1CacheType(CacheTypeEnum.CAFFEINE)
            .setL2CacheType(CacheTypeEnum.REDIS);

        // Caffeine、Redis、SyncPolicy 等配置对象已经在 CacheConfig 中初始化为 final
        // 如果需要设置属性，直接通过 getter 获取并设置即可

        return config;
    }

    private CacheSetting createCacheSetting(CacheModeEnum cacheMode) {
        L1CacheSetting l1Setting = new L1CacheSetting();
        l1Setting.setMaximumSize(1000L);
        l1Setting.setExpireTime(60L);
        l1Setting.setExpireTimeUnit(TimeUnit.SECONDS);

        L2CacheSetting l2Setting = new L2CacheSetting();
        l2Setting.setExpireTime(300L);
        l2Setting.setExpireTimeUnit(TimeUnit.SECONDS);

        CacheSetting setting = new CacheSetting();
        setting.setCacheModeEnum(cacheMode);
        setting.setL1CacheSetting(l1Setting);
        setting.setL2CacheSetting(l2Setting);

        return setting;
    }
}
