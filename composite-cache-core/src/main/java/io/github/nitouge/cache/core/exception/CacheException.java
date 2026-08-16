package io.github.nitouge.cache.core.exception;

/**
 * 缓存操作异常
 * 
 * <p>通用的缓存操作异常，用于包装各种缓存操作失败的场景。
 * 
 */
public class CacheException extends CompositeCacheException {

    private static final long serialVersionUID = 1L;

    public CacheException(String message) {
        super(message);
    }

    public CacheException(String message, Throwable cause) {
        super(message, cause);
    }

    public CacheException(String cacheName, Object cacheKey, String message) {
        super(cacheName, cacheKey, message);
    }

    public CacheException(String cacheName, Object cacheKey, String message, Throwable cause) {
        super(cacheName, cacheKey, message, cause);
    }
}
