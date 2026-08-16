package io.github.nitouge.cache.annotation.key;

import io.github.nitouge.cache.core.consts.CacheConsts;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/**
 * 自定义缓存 Key 生成器
 *
 * <p>生成格式：{@code ClassName:methodName:param1:param2:...}
 *
 * <h3>生成示例</h3>
 * <pre>
 * UserService:getUserById:123
 * ProductService:getProductsByIds:1,2,3
 * OrderService:createOrder:Order{id=1}
 * </pre>
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>包含类名和方法名，避免 Key 冲突</li>
 *   <li>支持基本类型数组</li>
 *   <li>支持集合和 Map</li>
 *   <li>自动处理 null 值</li>
 * </ul>
 *
 */
public class CustomCacheKeyGenerator implements CacheKeyGenerator {

    /**
     * 生成缓存 Key（完整版）
     *
     * <p>格式：{@code ClassName:methodName:param1:param2:...}
     *
     * @param target 目标对象
     * @param method 方法对象
     * @param params 方法参数
     * @return 缓存 Key 字符串
     */
    @Override
    public Object generate(Object target, Method method, Object... params) {
        StringBuilder key = new StringBuilder();
        
        // 添加类名和方法名
        key.append(target.getClass().getSimpleName())
           .append(CacheConsts.SPLIT_SINGLE)
           .append(method.getName());
        
        // 如果没有参数，直接返回
        if (params == null || params.length == 0) {
            return key.toString();
        }
        
        // 添加参数
        key.append(CacheConsts.SPLIT_SINGLE);
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                key.append(CacheConsts.SPLIT_SINGLE);
            }
            appendParam(key, params[i]);
        }
        
        return key.toString();
    }
    
    /**
     * 生成缓存 Key（简化版，用于 SpEL 表达式解析后的 Key）
     *
     * <p>直接返回 keyValue，不添加类名和方法名前缀。
     *
     * @param keyValue SpEL 表达式解析后的值
     * @return 缓存 Key
     */
    @Override
    public Object generate(Object keyValue) {
        if (keyValue == null) {
            return "null";
        }

        // 如果是集合、数组或 Map，格式化输出
        StringBuilder key = new StringBuilder();
        if (keyValue.getClass().isArray()) {
            appendArray(key, keyValue);
            return key.toString();
        } else if (keyValue instanceof Collection) {
            appendCollection(key, (Collection<?>) keyValue);
            return key.toString();
        } else if (keyValue instanceof Map) {
            appendMap(key, (Map<?, ?>) keyValue);
            return key.toString();
        }
        
        // 其他类型直接返回
        return keyValue;
    }
    
    /**
     * 添加参数到 Key
     */
    private void appendParam(StringBuilder key, Object param) {
        if (param == null) {
            key.append("null");
        } else if (param.getClass().isArray()) {
            appendArray(key, param);
        } else if (param instanceof Collection) {
            appendCollection(key, (Collection<?>) param);
        } else if (param instanceof Map) {
            appendMap(key, (Map<?, ?>) param);
        } else {
            key.append(param);
        }
    }

    /**
     * 添加数组到 Key
     */
    private void appendArray(StringBuilder key, Object array) {
        key.append("[");
        int length = Array.getLength(array);
        for (int i = 0; i < length; i++) {
            if (i > 0) {
                key.append(CacheConsts.COMMA);
            }
            Object element = Array.get(array, i);
            key.append(element);
        }
        key.append("]");
    }

    /**
     * 添加集合到 Key
     */
    private void appendCollection(StringBuilder key, Collection<?> collection) {
        key.append("[");
        int i = 0;
        for (Object element : collection) {
            if (i++ > 0) {
                key.append(CacheConsts.COMMA);
            }
            key.append(element);
        }
        key.append("]");
    }

    /**
     * 添加 Map 到 Key
     */
    private void appendMap(StringBuilder key, Map<?, ?> map) {
        key.append("{");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (i++ > 0) {
                key.append(CacheConsts.COMMA);
            }
            key.append(entry.getKey()).append("=").append(entry.getValue());
        }
        key.append("}");
    }
}
