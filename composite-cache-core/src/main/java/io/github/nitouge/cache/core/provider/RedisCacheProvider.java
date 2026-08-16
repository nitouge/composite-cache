package io.github.nitouge.cache.core.provider;


import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.consts.enums.CacheDataTypeEnum;
import io.github.nitouge.cache.core.impl.level2.RedissonRBucketCache;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

/**
 * Redis 缓存提供者
 */
@Slf4j
public class RedisCacheProvider extends AbstractCacheProvider<L2Cache, RedissonClient> {

    public RedisCacheProvider(CacheConfig cacheConfig) {
        super(cacheConfig);
    }

    @Override
    public L2Cache build(String cacheName, CacheSetting cacheSetting) {
        if (cacheSetting == null || cacheSetting.getL2CacheSetting() == null) {
            throw new IllegalArgumentException("L2缓存的cacheSetting/L2CacheSetting不能为null: " + cacheName);
        }
        CacheDataTypeEnum dataType = cacheSetting.getL2CacheSetting().getDataType();
        // 目前仅 String(RBucket) 已实现；Hash 等结构预留待实现，配置后先回退 String 保证可用
        // 旧实现遇到 Hash 会返回 null → 上层 NPE，此处修正为安全回退 + 告警
        if (dataType != null && dataType != CacheDataTypeEnum.DATA_TYPE_STRING) {
            log.warn("L2 dataType={} not implemented yet, fallback to String(RBucket), cacheName={}", dataType, cacheName);
        }
        return this.buildRedisStringCache(cacheName, cacheSetting);
    }

    private L2Cache buildRedisStringCache(String cacheName, CacheSetting cacheSetting) {
        RedissonClient redissonClient = this.getActualCacheClient();
        if (redissonClient == null) {
            log.warn("RedissonClient is null, cannot create cache for cacheName={}", cacheName);
            return null;
        }
        log.info("create a native Redisson RBucket instance, cacheName={}", cacheName);
        return new RedissonRBucketCache(cacheName, this.getCacheConfig(), cacheSetting, redissonClient);
    }
}
