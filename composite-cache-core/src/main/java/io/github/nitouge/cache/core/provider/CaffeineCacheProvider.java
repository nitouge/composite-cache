package io.github.nitouge.cache.core.provider;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.nitouge.cache.core.api.CacheLoader;
import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L1CacheSetting;
import io.github.nitouge.cache.core.consts.enums.CacheExpireModeEnum;
import io.github.nitouge.cache.core.consts.enums.CacheTypeEnum;
import io.github.nitouge.cache.core.impl.level1.CaffeineCache;
import io.github.nitouge.cache.core.loader.CompositeCacheLoader;
import io.github.nitouge.cache.core.support.expire.CacheExpiredListener;
import io.github.nitouge.cache.core.support.expire.DefaultCacheExpiredListener;
import io.github.nitouge.cache.core.pool.MdcForkJoinPool;
import lombok.extern.slf4j.Slf4j;


/**
 * Caffeine 缓存提供者
 *
 * <p>负责创建和配置 Caffeine 缓存实例。
 *
 */
@Slf4j
public class CaffeineCacheProvider extends AbstractCacheProvider<L1Cache, Void> {

    public CaffeineCacheProvider(CacheConfig cacheConfig) {
        super(cacheConfig);
    }

    @Override
    public L1Cache build(String cacheName, CacheSetting cacheSetting) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为null或空");
        }
        if (cacheSetting == null) {
            throw new IllegalArgumentException("缓存配置不能为null");
        }
        
        boolean manualCache = getCacheConfig().getCaffeine().isManualCache();
        
        if (manualCache) {
            return buildManualCache(cacheName, cacheSetting);
        } else {
            return buildLoadingCache(cacheName, cacheSetting);
        }
    }
    
    /**
     * 构建手动缓存（Manual Cache）
     */
    private L1Cache buildManualCache(String cacheName, CacheSetting cacheSetting) {
        CacheExpiredListener listener = new DefaultCacheExpiredListener();
        com.github.benmanes.caffeine.cache.Cache<Object, Object> cache = 
                buildCaffeineCache(cacheName, cacheSetting.getL1CacheSetting(), null, listener);
        
        log.debug("Manual Caffeine cache created: cacheName={}", cacheName);
        return new CaffeineCache(cacheName, getCacheConfig(), null, getCacheSyncPolicy(), cache);
    }
    
    /**
     * 构建自动加载缓存（Loading Cache）
     */
    private L1Cache buildLoadingCache(String cacheName, CacheSetting cacheSetting) {
        CompositeCacheLoader cacheLoader = createCacheLoader(cacheName, cacheSetting);
        CacheExpiredListener listener = new DefaultCacheExpiredListener();
        com.github.benmanes.caffeine.cache.Cache<Object, Object> cache = 
                buildCaffeineCache(cacheName, cacheSetting.getL1CacheSetting(), cacheLoader, listener);
        
        log.debug("Loading Caffeine cache created: cacheName={}", cacheName);
        return new CaffeineCache(cacheName, getCacheConfig(), cacheLoader, getCacheSyncPolicy(), cache);
    }
    
    /**
     * 创建缓存加载器
     */
    private CompositeCacheLoader createCacheLoader(String cacheName, CacheSetting cacheSetting) {
        CompositeCacheLoader cacheLoader = CompositeCacheLoader.newInstance(
                getCacheConfig().getInstanceId(),
                CacheTypeEnum.CAFFEINE.name(),
                cacheName,
                cacheSetting.getL1CacheSetting().getMaximumSize()
        );
        
        cacheLoader.setAllowNullValues(getCacheConfig().isAllowNullValues());
        cacheLoader.setCacheSyncPolicy(getCacheSyncPolicy());
        
        return cacheLoader;
    }

    /**
     * 构建原生 Caffeine 缓存对象
     *
     * @param cacheName 缓存名称
     * @param l1CacheSetting L1 缓存配置
     * @param cacheLoader 缓存加载器（可为 null）
     * @param listener 过期监听器
     * @return Caffeine 缓存实例
     */
    protected com.github.benmanes.caffeine.cache.Cache<Object, Object> buildCaffeineCache(
            String cacheName,
            L1CacheSetting l1CacheSetting,
            CacheLoader<Object, Object> cacheLoader,
            CacheExpiredListener listener) {

        // 构建Caffeine Builder
        Caffeine<Object, Object> cacheBuilder = Caffeine.newBuilder()
                .executor(MdcForkJoinPool.sharedPool())
                .initialCapacity(l1CacheSetting.getInitialCapacity())
                .maximumSize(l1CacheSetting.getMaximumSize());

        // 配置过期策略
        configureExpirePolicy(cacheBuilder, l1CacheSetting);

        // 配置移除监听器
        if (listener != null) {
            cacheBuilder.removalListener((key, value, cause) -> 
                    listener.onExpired(key, value, cause.name()));
        }

        // 构建缓存实例
        if (cacheLoader == null) {
            log.debug("Building manual Caffeine cache: cacheName={}", cacheName);
            return cacheBuilder.build();
        } else {
            log.debug("Building loading Caffeine cache: cacheName={}", cacheName);
            return cacheBuilder.build(key -> cacheLoader.load(key));
        }
    }
    
    /**
     * 配置过期策略
     */
    private void configureExpirePolicy(Caffeine<Object, Object> cacheBuilder, L1CacheSetting l1CacheSetting) {
        CacheExpireModeEnum expireMode = l1CacheSetting.getCacheExpireModeEnum();
        
        if (expireMode == CacheExpireModeEnum.WRITE) {
            cacheBuilder.expireAfterWrite(
                    l1CacheSetting.getExpireTime(), 
                    l1CacheSetting.getExpireTimeUnit());
        } else if (expireMode == CacheExpireModeEnum.ACCESS) {
            cacheBuilder.expireAfterAccess(
                    l1CacheSetting.getExpireTime(), 
                    l1CacheSetting.getExpireTimeUnit());
        }
    }
}
