package io.github.nitouge.cache.core.loader.task;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 发布消息任务包装器
 *
 * <p>包装ValueLoaderTask，用于判断是否需要发送缓存同步消息。
 *
 * <h3>设计目的</h3>
 * <ul>
 *   <li>区分数据来源：从缓存加载 vs 从数据源加载</li>
 *   <li>消息控制：仅在从数据源加载时发送同步消息</li>
 *   <li>避免污染：不会对L2缓存造成不必要的同步消息</li>
 * </ul>
 *
 */
@Slf4j
public class PublishMessageTask implements Callable<Object> {

    /**
     * 缓存名称
     */
    private final String cacheName;

    /**
     * 缓存key
     */
    private final Object key;

    /**
     * 获取原始ValueLoader
     */
    @Getter
    private final Callable<?> valueLoader;

    /**
     * 是否已执行：true表示已调用call()方法（即数据来自数据源回源，而非缓存命中）
     */
    private final AtomicBoolean executed = new AtomicBoolean(false);

    /**
     * 是否发送消息：默认true
     *
     * <p>注：executed=true 且 publishMsg=true 时，才发送消息
     *
     */
    @Setter
    private volatile boolean publishMsg = true;

    public PublishMessageTask(String cacheName, Object key, Callable<?> valueLoader) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("cacheName cannot be null or empty");
        }
        this.cacheName = cacheName;
        this.key = key;
        this.valueLoader = valueLoader;
    }

    @Override
    public Object call() throws Exception {
        if (valueLoader == null) {
            log.warn("ValueLoader is null, skip execution, cacheName={}, key={}", cacheName, key);
            return null;
        }

        long startTime = System.currentTimeMillis();
        try {
            Object result = valueLoader.call();
            // 标记已执行（数据来自数据源回源）
            executed.set(true);
            if (log.isDebugEnabled()) {
                long duration = System.currentTimeMillis() - startTime;
                log.debug("ValueLoader executed, cacheName={}, key={}, duration={}ms",
                        cacheName, key, duration);
            }
            return result;
        } catch (Exception e) {
            log.error("ValueLoader execution failed, cacheName={}, key={}", cacheName, key, e);
            throw e;
        }
    }

    /**
     * 判断是否需要发布消息
     *
     * <p>条件：executed=true 且 publishMsg=true
     * <p>说明：executed=true 表示执行了数据加载逻辑（而非从缓存获取）
     *
     * @return true-需要发布，false-不需要发布
     */
    public boolean isPublishMsg() {
        return executed.get() && publishMsg;
    }

}
