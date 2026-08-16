package io.github.nitouge.cache.annotation.validation;

import io.github.nitouge.cache.annotation.*;
import io.github.nitouge.cache.annotation.exception.AnnotationExceptionFactory;
import io.github.nitouge.cache.annotation.exception.CacheAnnotationException;
import org.springframework.util.StringUtils;

/**
 * 注解配置验证器
 * 
 * <p>验证缓存注解的配置是否合法，在切面执行前进行验证，提前发现配置错误。
 * 
 */
public class AnnotationConfigValidator {
    
    private AnnotationConfigValidator() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
    
    /**
     * 验证 @CacheAble 注解
     */
    public static void validate(CacheAble annotation, String methodSignature) {
        // 验证缓存名称
        if (!StringUtils.hasText(annotation.cacheName())) {
            throw AnnotationExceptionFactory.cacheNameEmpty("@CacheAble", methodSignature);
        }
        
        // 验证 Key 表达式
        if (!StringUtils.hasText(annotation.keyExpr())) {
            throw AnnotationExceptionFactory.keyExprEmpty("@CacheAble", methodSignature);
        }
        
        // 验证 L1 配置
        Cache_L1 l1Config = annotation.cacheL1();
        if (l1Config != null) {
            validateL1Config(l1Config, "@CacheAble", methodSignature);
        }

        // 验证 L2 配置
        Cache_L2 l2Config = annotation.cacheL2();
        if (l2Config != null) {
            validateL2Config(l2Config, "@CacheAble", methodSignature);
        }
    }
    
    /**
     * 验证 @CachePut 注解
     */
    public static void validate(CachePut annotation, String methodSignature) {
        if (!StringUtils.hasText(annotation.cacheName())) {
            throw AnnotationExceptionFactory.cacheNameEmpty("@CachePut", methodSignature);
        }
        
        if (!StringUtils.hasText(annotation.keyExpr())) {
            throw AnnotationExceptionFactory.keyExprEmpty("@CachePut", methodSignature);
        }
    }
    
    /**
     * 验证 @CacheEvict 注解
     */
    public static void validate(CacheEvict annotation, String methodSignature) {
        if (!StringUtils.hasText(annotation.cacheName())) {
            throw AnnotationExceptionFactory.cacheNameEmpty("@CacheEvict", methodSignature);
        }
        
        // 如果不是 removeAll，则必须有 keyExpr
        if (!annotation.removeAll() && !StringUtils.hasText(annotation.keyExpr())) {
            throw new CacheAnnotationException(
                "@CacheEvict", 
                methodSignature, 
                "keyExpr cannot be empty when removeAll=false"
            );
        }
    }
    
    /**
     * 验证 @BatchCacheAble 注解
     */
    public static void validate(BatchCacheAble annotation, String methodSignature) {
        if (!StringUtils.hasText(annotation.cacheName())) {
            throw AnnotationExceptionFactory.cacheNameEmpty("@BatchCacheAble", methodSignature);
        }
        
        if (!StringUtils.hasText(annotation.keyExtractor())) {
            throw new CacheAnnotationException(
                "@BatchCacheAble", 
                methodSignature, 
                "keyExtractor cannot be empty"
            );
        }
        
        // 验证 L1 配置
        Cache_L1 l1Config = annotation.cacheL1();
        if (l1Config != null) {
            validateL1Config(l1Config, "@BatchCacheAble", methodSignature);
        }

        // 验证 L2 配置
        Cache_L2 l2Config = annotation.cacheL2();
        if (l2Config != null) {
            validateL2Config(l2Config, "@BatchCacheAble", methodSignature);
        }
    }

    /**
     * 验证 L1 配置
     */
    private static void validateL1Config(Cache_L1 config, String annotationType, String methodSignature) {
        if (config.TTL() < 0) {
            throw AnnotationExceptionFactory.invalidConfig(
                annotationType, 
                methodSignature, 
                "L1.TTL", 
                config.TTL(), 
                "TTL cannot be negative"
            );
        }
        
        if (config.maximumSize() <= 0) {
            throw AnnotationExceptionFactory.invalidConfig(
                annotationType, 
                methodSignature, 
                "L1.maximumSize", 
                config.maximumSize(), 
                "maximumSize must be greater than 0"
            );
        }
        
        if (config.initialCapacity() < 0) {
            throw AnnotationExceptionFactory.invalidConfig(
                annotationType, 
                methodSignature, 
                "L1.initialCapacity", 
                config.initialCapacity(), 
                "initialCapacity cannot be negative"
            );
        }
    }
    
    /**
     * 验证 L2 配置
     */
    private static void validateL2Config(Cache_L2 config, String annotationType, String methodSignature) {
        if (config.TTL() < 0) {
            throw AnnotationExceptionFactory.invalidConfig(
                annotationType, 
                methodSignature, 
                "L2.TTL", 
                config.TTL(), 
                "TTL cannot be negative"
            );
        }
    }
}
