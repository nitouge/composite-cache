package io.github.nitouge.cache.core.exception.factory;

import io.github.nitouge.cache.core.exception.CacheConfigException;

/**
 * 缓存异常工厂类。
 *
 * <p>提供配置校验场景下的异常创建方法（主要由 {@code CacheConfigValidator} 使用）。
 * 其余异常（{@code CacheException}、{@code RedisTrylockFailException} 等）在各自抛出点直接构造，
 * 不再经由本工厂，以避免大量未使用的工厂方法堆积。
 *
 */
public final class CacheExceptionFactory {

    private CacheExceptionFactory() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * 创建配置异常
     */
    public static CacheConfigException configError(String message) {
        return new CacheConfigException(message);
    }

    /**
     * 创建配置异常（带配置项）
     */
    public static CacheConfigException configError(String configKey, Object configValue, String message) {
        return new CacheConfigException(configKey, configValue, message);
    }
}
