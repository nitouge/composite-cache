package io.github.nitouge.cache.core.api;

/**
 * 缓存同步消息监听器。
 *
 * <p>用于接收并处理多节点间的缓存同步消息（如本地缓存失效广播），{@code <T>} 为消息体类型。
 *
 * @param <T> 消息体类型
 */
public interface MessageListener<T> {

    /**
     * 缓存同步消息处理
     */
    void onMessage(T message);
}
