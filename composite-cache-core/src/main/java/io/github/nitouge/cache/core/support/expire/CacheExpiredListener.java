package io.github.nitouge.cache.core.support.expire;

/**
 * 缓存过期监听器
 */
public interface CacheExpiredListener<K, V> {

    /**
     * 缓存过期后触发
     */
    void onExpired(K key, V value, String removalCause);
}
