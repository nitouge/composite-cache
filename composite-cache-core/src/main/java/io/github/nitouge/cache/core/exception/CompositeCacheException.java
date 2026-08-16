package io.github.nitouge.cache.core.exception;

import lombok.Getter;

/**
 * 组合缓存基础异常类
 * 
 * <p>所有缓存相关异常的基类，提供统一的异常处理机制。
 * 
 */
@Getter
public class CompositeCacheException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 错误码
     */
    private final int code;

    /**
     * 缓存名称
     */
    private final String cacheName;

    /**
     * 缓存Key
     */
    private final Object cacheKey;

    public CompositeCacheException(String message) {
        super(message);
        this.code = 0;
        this.cacheName = null;
        this.cacheKey = null;
    }

    public CompositeCacheException(String message, Throwable cause) {
        super(message, cause);
        this.code = 0;
        this.cacheName = null;
        this.cacheKey = null;
    }

    public CompositeCacheException(int code, String message) {
        super(message);
        this.code = code;
        this.cacheName = null;
        this.cacheKey = null;
    }

    public CompositeCacheException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.cacheName = null;
        this.cacheKey = null;
    }

    public CompositeCacheException(String cacheName, Object cacheKey, String message) {
        super(buildMessage(cacheName, cacheKey, message));
        this.code = 0;
        this.cacheName = cacheName;
        this.cacheKey = cacheKey;
    }

    public CompositeCacheException(String cacheName, Object cacheKey, String message, Throwable cause) {
        super(buildMessage(cacheName, cacheKey, message), cause);
        this.code = 0;
        this.cacheName = cacheName;
        this.cacheKey = cacheKey;
    }

    /**
     * 构建异常消息
     */
    private static String buildMessage(String cacheName, Object cacheKey, String message) {
        StringBuilder sb = new StringBuilder();
        if (cacheName != null) {
            sb.append("[cacheName=").append(cacheName).append("] ");
        }
        if (cacheKey != null) {
            sb.append("[key=").append(cacheKey).append("] ");
        }
        sb.append(message);
        return sb.toString();
    }
}
