package io.github.nitouge.cache.core.provider;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L1CacheSetting;
import io.github.nitouge.cache.core.consts.enums.CacheExpireModeEnum;
import io.github.nitouge.cache.core.consts.enums.CacheTypeEnum;
import io.github.nitouge.cache.core.impl.level1.GuavaCache;
import io.github.nitouge.cache.core.loader.CompositeCacheLoader;
import io.github.nitouge.cache.core.support.expire.CacheExpiredListener;
import io.github.nitouge.cache.core.support.expire.DefaultCacheExpiredListener;
import lombok.extern.slf4j.Slf4j;

/**
 * Guava 缓存提供者
 *
 * <p>负责创建和配置 Guava 缓存实例，与 {@link CaffeineCacheProvider} 行为对等。
 *
 */
@Slf4j
public class GuavaCacheProvider extends AbstractCacheProvider<L1Cache, Void> {

    public GuavaCacheProvider(CacheConfig cacheConfig) {
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

        boolean manualCache = getCacheConfig().getGuava().isManualCache();

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
        com.google.common.cache.Cache<Object, Object> cache =
                buildGuavaCache(cacheName, cacheSetting.getL1CacheSetting(), null, listener);

        log.debug("Manual Guava cache created: cacheName={}", cacheName);
        return new GuavaCache(cacheName, getCacheConfig(), null, getCacheSyncPolicy(), cache);
    }

    /**
     * 构建自动加载缓存（Loading Cache）
     */
    private L1Cache buildLoadingCache(String cacheName, CacheSetting cacheSetting) {
        CompositeCacheLoader cacheLoader = createCacheLoader(cacheName, cacheSetting);
        CacheExpiredListener listener = new DefaultCacheExpiredListener();
        com.google.common.cache.Cache<Object, Object> cache =
                buildGuavaCache(cacheName, cacheSetting.getL1CacheSetting(), cacheLoader, listener);

        log.debug("Loading Guava cache created: cacheName={}", cacheName);
        return new GuavaCache(cacheName, getCacheConfig(), cacheLoader, getCacheSyncPolicy(), cache);
    }

    /**
     * 创建缓存加载器
     */
    private CompositeCacheLoader createCacheLoader(String cacheName, CacheSetting cacheSetting) {
        CompositeCacheLoader cacheLoader = CompositeCacheLoader.newInstance(
                getCacheConfig().getInstanceId(),
                CacheTypeEnum.GUAVA.name(),
                cacheName,
                cacheSetting.getL1CacheSetting().getMaximumSize()
        );

        cacheLoader.setAllowNullValues(getCacheConfig().isAllowNullValues());
        cacheLoader.setCacheSyncPolicy(getCacheSyncPolicy());

        return cacheLoader;
    }

    /**
     * 构建原生 Guava 缓存对象
     *
     * @param cacheName      缓存名称
     * @param l1CacheSetting L1 缓存配置
     * @param cacheLoader    缓存加载器（可为 null，为 null 时为手动缓存）
     * @param listener       过期监听器
     * @return Guava 缓存实例
     */
    @SuppressWarnings("unchecked")
    protected com.google.common.cache.Cache<Object, Object> buildGuavaCache(
            String cacheName,
            L1CacheSetting l1CacheSetting,
            io.github.nitouge.cache.core.api.CacheLoader<Object, Object> cacheLoader,
            CacheExpiredListener listener) {

        // 构建Guava CacheBuilder
        CacheBuilder<Object, Object> cacheBuilder = CacheBuilder.newBuilder()
                .initialCapacity(l1CacheSetting.getInitialCapacity())
                .maximumSize(l1CacheSetting.getMaximumSize());

        // 启用统计，便于 refreshAllExpiredCache 等日志输出
        cacheBuilder.recordStats();

        // 配置过期策略
        configureExpirePolicy(cacheBuilder, l1CacheSetting);

        // 配置移除监听器
        if (listener != null) {
            cacheBuilder.removalListener(notification ->
                    listener.onExpired(notification.getKey(), notification.getValue(), notification.getCause().name()));
        }

        // 构建缓存实例
        if (cacheLoader == null) {
            log.debug("Building manual Guava cache: cacheName={}", cacheName);
            return cacheBuilder.build();
        } else {
            log.debug("Building loading Guava cache: cacheName={}", cacheName);
            return cacheBuilder.build(new CacheLoader<Object, Object>() {
                @Override
                public Object load(Object key) {
                    // Guava 不允许 load 返回 null；当 cacheLoader.load 返回 null 时
                    // Guava 会抛出 InvalidCacheLoadException，由 GuavaCache 统一捕获按未命中处理
                    return cacheLoader.load(key);
                }
            });
        }
    }

    /**
     * 配置过期策略
     */
    private void configureExpirePolicy(CacheBuilder<Object, Object> cacheBuilder, L1CacheSetting l1CacheSetting) {
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
