package io.github.nitouge.cache.core.wrapper;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 防穿透用的"空值占位"无状态单例：用于缓存"查无结果"这一事实，
 * 使后续相同 key 的查询命中该占位值而直接返回空，拦截对底层数据源的重复回源。
 *
 * <p>{@link Serializable} 以便随缓存值在 L2 等场景下序列化；其 {@link #equals(Object) equals} 按类型相等，
 * 故反序列化产生的新实例与单例语义等价。
 *
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class NullValueWrapper implements Serializable {

    /** 全局共享的空值占位单例。 */
    public static final NullValueWrapper NULL_VALUE_WRAPPER = new NullValueWrapper();


    @Override
    public int hashCode() {
        return NullValueWrapper.class.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        // NullValue 为无状态单例语义：任意 NullValueWrapper 实例彼此相等（含反序列化产生的新实例），
        // 但与 null 不相等（遵守 Object.equals 契约：x.equals(null) 必须为 false）。
        return obj instanceof NullValueWrapper;
    }

    @Override
    public String toString() {
        return "_NULL_";
    }
}
