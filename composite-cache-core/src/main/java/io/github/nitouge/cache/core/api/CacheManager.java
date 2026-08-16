package io.github.nitouge.cache.core.api;


import io.github.nitouge.cache.core.config.setting.CacheSetting;

import java.util.Collection;

/**
 * 缓存管理器
 * 允许通过缓存名称来获的对应的 {@link Cache}.
 */
public interface CacheManager {

    /**
     * 根据缓存名称返回对应的{@link Cache}，如果没有找到就新建一个并放到容器
     *
     * @param cacheName    缓存名称
     * @param cacheSetting 多级缓存配置
     * @return {@link Cache}
     */
    Cache getMissingCache(String cacheName, CacheSetting cacheSetting);

    /**
     * 根据缓存名称返回对应的{@link Collection}.
     *
     * @param cacheName 缓存的名称 (不能为 {@code null})
     * @return 返回对应名称的Cache, 如果没找到返回 {@code null}
     */
    default Cache getCache(String cacheName) {
        return this.getMissingCache(cacheName, null);
    }

    /**
     * 获取所有缓存名称的集合
     *
     * @return 所有缓存名称的集合
     */
    Collection<String> getCacheNames();
}
