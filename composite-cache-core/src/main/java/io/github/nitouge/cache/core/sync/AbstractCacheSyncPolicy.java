package io.github.nitouge.cache.core.sync;

import io.github.nitouge.cache.core.api.CacheSyncPolicy;
import io.github.nitouge.cache.core.api.MessageListener;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.sync.listener.CacheSyncMessage;
import lombok.Getter;
import lombok.experimental.Accessors;

/**
 * 缓存同步策略抽象基类
 *
 * <p>提供缓存同步策略的通用实现，子类只需实现具体的消息发布和订阅逻辑
 *
 * @param <C> 实际客户端类型
 */
@Getter
@Accessors(chain = true)
public abstract class AbstractCacheSyncPolicy<C> implements CacheSyncPolicy<C> {

    /**
     * 缓存配置
     */
    private CacheConfig cacheConfig;

    /**
     * 缓存消息监听器
     */
    private MessageListener<CacheSyncMessage> cacheMessageListener;

    /**
     * 实际的客户端对象（如 RedissonClient、Properties 等）
     */
    private C actualClient;

    @Override
    public CacheSyncPolicy<C> setCacheConfig(CacheConfig cacheConfig) {
        this.cacheConfig = cacheConfig;
        return this;
    }

    @Override
    public CacheSyncPolicy<C> setCacheMessageListener(MessageListener<CacheSyncMessage> cacheMessageListener) {
        this.cacheMessageListener = cacheMessageListener;
        return this;
    }

    @Override
    public CacheSyncPolicy<C> setActualClient(C actualClient) {
        this.actualClient = actualClient;
        return this;
    }
}
