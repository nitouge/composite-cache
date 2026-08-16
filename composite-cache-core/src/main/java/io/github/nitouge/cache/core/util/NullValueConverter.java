package io.github.nitouge.cache.core.util;

import io.github.nitouge.cache.core.wrapper.NullValueWrapper;

/**
 * NullValue 工具类
 */
public class NullValueConverter {

    /**
     * 转换为存储值
     */
    public static Object toStoreValue(Object useValue, boolean allowNullValues, String cacheName) {
        if (useValue == null) {
            if (allowNullValues) {
                return NullValueWrapper.NULL_VALUE_WRAPPER;
            }
            throw new IllegalArgumentException("Cache '" + cacheName + "' is configured to not allow null values but null was provided");
        }
        return useValue;
    }

    /**
     * 从存储值解析为具体值
     */
    public static Object fromStoreValue(Object storeValue, boolean allowNullValues) {
        if (allowNullValues && storeValue instanceof NullValueWrapper) {
            return null;
        }
        return storeValue;
    }
}
