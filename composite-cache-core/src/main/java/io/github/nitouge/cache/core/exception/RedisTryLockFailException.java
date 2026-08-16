package io.github.nitouge.cache.core.exception;

import lombok.Getter;

/**
 * Redis分布式锁获取失败异常
 * 
 * <p>当尝试获取Redis分布式锁失败时抛出此异常。
 * <p>这通常发生在高并发场景下，多个线程同时尝试加载同一个缓存Key的数据。
 * 
 * <h3>使用场景</h3>
 * <ul>
 *   <li>快速失败策略：在tryLock模式下，拦截重复请求，避免大量线程阻塞</li>
 *   <li>防止缓存击穿：当缓存失效时，只允许一个线程查询DB</li>
 * </ul>
 * 
 */
@Getter
public class RedisTryLockFailException extends CompositeCacheException {

    private static final long serialVersionUID = 1L;

    /**
     * 锁Key
     */
    private final Object lockKey;

    /**
     * 等待时间（毫秒）
     */
    private final long waitTime;

    public RedisTryLockFailException(String message) {
        super(message);
        this.lockKey = null;
        this.waitTime = 0;
    }

    public RedisTryLockFailException(Object lockKey, String message) {
        super(buildMessage(lockKey, message));
        this.lockKey = lockKey;
        this.waitTime = 0;
    }

    public RedisTryLockFailException(String cacheName, Object lockKey, String message) {
        super(cacheName, lockKey, message);
        this.lockKey = lockKey;
        this.waitTime = 0;
    }

    public RedisTryLockFailException(String cacheName, Object lockKey, long waitTime, String message) {
        super(cacheName, lockKey, buildMessageWithWaitTime(waitTime, message));
        this.lockKey = lockKey;
        this.waitTime = waitTime;
    }

    /**
     * 构建异常消息
     */
    private static String buildMessage(Object lockKey, String message) {
        return String.format("Failed to acquire lock for key [%s]: %s", lockKey, message);
    }

    /**
     * 构建带等待时间的异常消息
     */
    private static String buildMessageWithWaitTime(long waitTime, String message) {
        return String.format("[waitTime=%dms] %s", waitTime, message);
    }
}
