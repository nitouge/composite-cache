package io.github.nitouge.cache.core.provider;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheProvider;
import io.github.nitouge.cache.core.api.CacheSyncPolicy;
import io.github.nitouge.cache.core.config.CacheConfig;
import lombok.Getter;

/**
 * 缓存提供者抽象基类
 *
 * <p>提供缓存提供者的通用实现，子类只需实现具体的缓存构建逻辑。
 *
 * @param <T> 缓存类型
 * @param <C> 实际缓存客户端类型
 */
@Getter
public abstract class AbstractCacheProvider<T extends Cache, C> implements CacheProvider<T, C> {

    private final CacheConfig cacheConfig;

    private CacheSyncPolicy<?> cacheSyncPolicy;

    private volatile C actualCacheClient;

    public AbstractCacheProvider(CacheConfig cacheConfig) {
        if (cacheConfig == null) {
            throw new IllegalArgumentException("缓存配置不能为null");
        }
        this.cacheConfig = cacheConfig;
    }

    @Override
    public CacheProvider<T, C> setCacheSyncPolicy(CacheSyncPolicy<?> cacheSyncPolicy) {
        this.cacheSyncPolicy = cacheSyncPolicy;
        return this;
    }

    @Override
    public CacheProvider<T, C> setActualCacheClient(C actualCacheClient) {
        this.actualCacheClient = actualCacheClient;
        return this;
    }
}
