package io.github.nitouge.cache.core.api;

import java.util.Collection;
import java.util.Set;

/**
 * L1（本地）缓存接口。
 *
 * <p>在 {@link Cache} 基础上扩展本地缓存特有能力：缓存同步策略 {@link CacheSyncPolicy}（用于多节点间
 * 失效广播）、加载器 {@link CacheLoader}（LoadingCache 模式下的回源加载）、主动刷新，以及
 * {@link #keys()}/{@link #values()} 全量视图遍历。
 *
 * @see L2Cache 二级缓存（分布式缓存）
 */
public interface L1Cache extends Cache {

    CacheSyncPolicy<?> getCacheSyncPolicy();

    CacheLoader<Object, Object> getCacheLoader();

    boolean isLoadingCache();

    /**
     * 包含 evict 和 clear，不会发送消息同步缓存
     */
    void invalidateCache(Object key);

    /**
     * 当 isLoadingCache 返回 true 时执行
     * @param key 缓存键
     */
    void refresh(Object key);

    /**
     * 当 isLoadingCache 返回 true 时执行
     */
    void refreshAll();

    /**
     * 当 isLoadingCache 返回 true 时执行
     */
    void refreshExpiredCache(Object key);

    /**
     * 当 isLoadingCache 返回 true 时执行
     */
    void refreshAllExpiredCache();

    /**
     * 缓存数量
     * @return 缓存项数量
     */
    long size();

    default Set<Object> keys() {
        return null;
    }

    default Collection<Object> values() {
        return null;
    }

}
