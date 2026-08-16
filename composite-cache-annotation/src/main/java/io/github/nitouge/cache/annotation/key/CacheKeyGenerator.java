package io.github.nitouge.cache.annotation.key;

import java.lang.reflect.Method;

/**
 * 缓存 Key 生成器接口
 *
 * <p>用于生成缓存 Key，支持多种生成策略。
 *
 * <h3>默认策略</h3>
 * <ul>
 *   <li>无参数 → 返回 {@link DefaultKey#EMPTY}</li>
 *   <li>单个参数（非数组） → 直接返回参数</li>
 *   <li>多个参数或数组 → 返回 {@link DefaultKey}</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 自定义 Key 生成器
 * public class MyKeyGenerator implements CacheKeyGenerator {
 *     @Override
 *     public Object generate(Object target, Method method, Object... params) {
 *         return "custom:" + Arrays.toString(params);
 *     }
 * }
 * }</pre>
 *
 * @see DefaultKey
 * @see CustomCacheKeyGenerator
 */
public interface CacheKeyGenerator {

    /**
     * 生成缓存 Key
     *
     * @param target 目标对象
     * @param method 方法对象
     * @param params 方法参数
     * @return 缓存 Key
     */
    default Object generate(Object target, Method method, Object... params) {
        if (params == null || params.length == 0) {
            return DefaultKey.EMPTY;
        }

        // 单个参数且非数组，直接返回
        if (params.length == 1) {
            Object param = params[0];
            if (param != null && !param.getClass().isArray()) {
                return param;
            }
        }

        // 多个参数或数组，返回 DefaultKey
        return new DefaultKey(params);
    }

    /**
     * 简化版生成方法（用于 SpEL 表达式解析后的 Key）
     *
     * @param keyValue SpEL 表达式解析后的值
     * @return 缓存 Key
     */
    default Object generate(Object keyValue) {
        if (keyValue == null) {
            return DefaultKey.EMPTY;
        }
        return keyValue;
    }
}
