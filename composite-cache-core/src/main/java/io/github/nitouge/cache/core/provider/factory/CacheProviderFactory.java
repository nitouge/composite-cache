package io.github.nitouge.cache.core.provider.factory;

import io.github.nitouge.cache.core.api.CacheProvider;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.consts.enums.CacheTypeEnum;
import io.github.nitouge.cache.core.provider.CaffeineCacheProvider;
import io.github.nitouge.cache.core.provider.GuavaCacheProvider;
import io.github.nitouge.cache.core.provider.RedisCacheProvider;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 缓存 Provider 工厂：依据 {@link CacheTypeEnum} 创建对应的 {@link CacheProvider}（CAFFEINE / GUAVA / REDIS）。
 *
 * <p>对未知或为 null 的类型采用 fail-fast 策略，直接抛出异常以暴露配置错误，而非静默返回 null。
 *
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CacheProviderFactory {

    /**
     * 根据缓存类型创建对应的 Provider。
     *
     * <p>未知 / 为 null 的 cacheType 属于<b>配置错误</b>，直接 fail-fast 抛出，避免静默返回 null 导致后续 NPE。
     */
    public static CacheProvider<?, ?> getCacheProvider(CacheTypeEnum cacheType, CacheConfig cacheConfig) {
        if (cacheType == null) {
            throw new IllegalArgumentException("cacheType 不能为空（支持：CAFFEINE / GUAVA / REDIS）");
        }
        switch (cacheType) {
            case CAFFEINE:
                return new CaffeineCacheProvider(cacheConfig);
            case GUAVA:
                return new GuavaCacheProvider(cacheConfig);
            case REDIS:
                return new RedisCacheProvider(cacheConfig);
            default:
                throw new IllegalArgumentException("不支持的 cacheType：" + cacheType + "（支持：CAFFEINE / GUAVA / REDIS）");
        }
    }
}
