package io.github.nitouge.cache.core.api;


import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;


/**
 * 缓存构建器
 *
 * @param <T> 缓存类型
 * @param <C> 实际缓存客户端类型，如 RedissonClient 等；L1 缓存可使用 Void
 */
public interface CacheProvider<T extends Cache, C> {

    /**
     * 构建指定名称的缓存对象
     */
    T build(String cacheName, CacheSetting cacheSetting);

    /**
     * 获取缓存配置（由构造器注入，只读）
     */
    CacheConfig getCacheConfig();

    /**
     * 获取缓存同步策略
     */
    CacheSyncPolicy<?> getCacheSyncPolicy();

    /**
     * 设置缓存同步策略
     */
    CacheProvider<T, C> setCacheSyncPolicy(CacheSyncPolicy<?> cacheSyncPolicy);

    /**
     * 获取真实的缓存Client实例
     * 注：主要用于二级缓存，一级缓存如果有需要可以使用
     */
    C getActualCacheClient();

    /**
     * 设置真实的缓存Client实例
     * 注：主要是为了在使用二级缓存时留一个扩展点，可以直接设置应用中已经存在的缓存Client实例，如：RedissonClient 等
     */
    CacheProvider<T, C> setActualCacheClient(C actualCacheClient);
}
