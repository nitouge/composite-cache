package io.github.nitouge.cache.annotation.exception;

import io.github.nitouge.cache.core.exception.CompositeCacheException;
import lombok.Getter;

/**
 * 缓存注解异常
 * 
 * <p>当缓存注解配置或执行出错时抛出此异常。
 * 
 * <h3>使用场景</h3>
 * <ul>
 *   <li>注解配置不合法（cacheName 为空、keyExpr 为空等）</li>
 *   <li>SpEL 表达式解析失败</li>
 *   <li>缓存操作执行失败</li>
 *   <li>Key 生成失败</li>
 * </ul>
 *
 */
@Getter
public class CacheAnnotationException extends CompositeCacheException {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 注解类型（如：@CacheAble、@CachePut）
     */
    private final String annotationType;
    
    /**
     * 方法签名（如：UserService.getUserById）
     */
    private final String methodSignature;
    
    public CacheAnnotationException(String annotationType, String methodSignature, String message) {
        super(buildMessage(annotationType, methodSignature, message));
        this.annotationType = annotationType;
        this.methodSignature = methodSignature;
    }
    
    public CacheAnnotationException(String annotationType, String methodSignature, String message, Throwable cause) {
        super(buildMessage(annotationType, methodSignature, message), cause);
        this.annotationType = annotationType;
        this.methodSignature = methodSignature;
    }
    
    /**
     * 构建异常消息
     */
    private static String buildMessage(String annotationType, String methodSignature, String message) {
        return String.format("[%s] [method=%s] %s", annotationType, methodSignature, message);
    }
}
