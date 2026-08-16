package io.github.nitouge.cache.core.api;


import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.sync.listener.CacheSyncMessage;


/**
 * 缓存同步策略
 *
 * @param <C> 实际客户端类型，如 RedissonClient、Properties 等
 */
public interface CacheSyncPolicy<C> {

    /**
     * 获取缓存配置
     */
    CacheConfig getCacheConfig();

    /**
     * 设置缓存配置
     */
    CacheSyncPolicy<C> setCacheConfig(CacheConfig cacheConfig);

    /**
     * 获取缓存消息监听器
     */
    MessageListener<CacheSyncMessage> getCacheMessageListener();

    /**
     * 设置缓存消息监听器
     */
    CacheSyncPolicy<C> setCacheMessageListener(MessageListener<CacheSyncMessage> cacheMessageListener);

    /**
     * 获取真实的Client实例
     */
    C getActualClient();

    /**
     * 设置真实的Client实例
     * 注：留一个扩展点，可以直接设置应用中已经存在的Client实例，如：RedissonClient、Properties 等
     */
    CacheSyncPolicy<C> setActualClient(C actualClient);

    /**
     * 建立连接，并订阅消息
     */
    void run();

    /**
     * 发布，缓存变更时通知其他节点清理本地缓存
     */
    void publish(CacheSyncMessage message);

    /**
     * 断开连接
     */
    void close();

}
