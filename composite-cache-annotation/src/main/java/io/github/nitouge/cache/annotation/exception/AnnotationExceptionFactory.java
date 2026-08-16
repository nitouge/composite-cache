package io.github.nitouge.cache.annotation.exception;

/**
 * 注解异常工厂类
 * 
 * <p>提供统一的异常创建方法，简化异常抛出代码。
 * 
 */
public class AnnotationExceptionFactory {
    
    private AnnotationExceptionFactory() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
    
    /**
     * 缓存名称为空
     */
    public static CacheAnnotationException cacheNameEmpty(String annotationType, String methodSignature) {
        return new CacheAnnotationException(
            annotationType,
            methodSignature,
            "缓存名称不能为空"
        );
    }

    /**
     * 缓存 Key 为空
     */
    public static CacheAnnotationException cacheKeyNull(String annotationType, String methodSignature, Object key) {
        return new CacheAnnotationException(
            annotationType,
            methodSignature,
            String.format("缓存 Key 不能为 null，实际值: %s", key)
        );
    }

    /**
     * Key 表达式为空
     */
    public static CacheAnnotationException keyExprEmpty(String annotationType, String methodSignature) {
        return new CacheAnnotationException(
            annotationType,
            methodSignature,
            "keyExpr 不能为空"
        );
    }

    /**
     * SpEL 表达式解析失败
     */
    public static CacheAnnotationException spelParseError(String annotationType, String methodSignature,
                                                         String expression, Throwable cause) {
        return new CacheAnnotationException(
            annotationType,
            methodSignature,
            String.format("SpEL 表达式解析失败: %s", expression),
            cause
        );
    }

    /**
     * 缓存操作失败
     */
    public static CacheAnnotationException cacheOperationFailed(String annotationType, String methodSignature,
                                                               String operation, Throwable cause) {
        return new CacheAnnotationException(
            annotationType,
            methodSignature,
            String.format("缓存操作 '%s' 失败", operation),
            cause
        );
    }

    /**
     * 配置不合法
     */
    public static CacheAnnotationException invalidConfig(String annotationType, String methodSignature,
                                                        String configName, Object configValue, String reason) {
        return new CacheAnnotationException(
            annotationType,
            methodSignature,
            String.format("配置不合法 [%s=%s]: %s", configName, configValue, reason)
        );
    }
}
