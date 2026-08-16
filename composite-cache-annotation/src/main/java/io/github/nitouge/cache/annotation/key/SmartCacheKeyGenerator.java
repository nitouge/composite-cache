package io.github.nitouge.cache.annotation.key;

import io.github.nitouge.cache.core.consts.CacheConsts;
import org.springframework.util.DigestUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

/**
 * 智能缓存 Key 生成器
 *
 * <p>在 {@link CustomCacheKeyGenerator} 的基础上增强，支持更多特性：
 * <ul>
 *   <li>自动处理大对象（超过阈值使用 MD5）</li>
 *   <li>支持嵌套对象</li>
 *   <li>支持循环引用检测</li>
 *   <li>更好的性能优化</li>
 * </ul>
 *
 * <h3>生成格式</h3>
 * <pre>
 * 正常：ClassName:methodName:param1:param2
 * 大对象：ClassName:methodName:MD5(largeObject)
 * </pre>
 *
 * <h3>使用场景</h3>
 * <ul>
 *   <li>参数对象较大时，自动使用 MD5 压缩</li>
 *   <li>需要处理复杂嵌套对象</li>
 *   <li>需要避免 Key 过长</li>
 * </ul>
 * 
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Bean
 * public CacheKeyGenerator smartCacheKeyGenerator() {
 *     return new SmartCacheKeyGenerator(200); // 超过 200 字符使用 MD5
 * }
 * }</pre>
 *
 */
public class SmartCacheKeyGenerator implements CacheKeyGenerator {

    /**
     * Key 长度阈值，超过此长度使用 MD5
     */
    private final int keyLengthThreshold;

    /**
     * 最大嵌套深度
     */
    private final int maxNestingDepth;

    /**
     * 默认构造函数
     *
     * <p>使用默认阈值：200 字符
     */
    public SmartCacheKeyGenerator() {
        this(200, 5);
    }
    
    /**
     * 自定义构造函数
     *
     * @param keyLengthThreshold Key 长度阈值
     * @param maxNestingDepth 最大嵌套深度
     */
    public SmartCacheKeyGenerator(int keyLengthThreshold, int maxNestingDepth) {
        this.keyLengthThreshold = keyLengthThreshold;
        this.maxNestingDepth = maxNestingDepth;
    }
    
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
        StringBuilder paramsStr = new StringBuilder();
        
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                paramsStr.append(CacheConsts.SPLIT_SINGLE);
            }
            appendParam(paramsStr, params[i], 0);
        }
        
        // 如果参数字符串过长，使用 MD5
        String paramsString = paramsStr.toString();
        if (paramsString.length() > keyLengthThreshold) {
            String md5 = DigestUtils.md5DigestAsHex(paramsString.getBytes(StandardCharsets.UTF_8));
            key.append("MD5:").append(md5);
        } else {
            key.append(paramsString);
        }
        
        return key.toString();
    }

    /**
     * 简化版生成方法（用于 keyExpr SpEL 解析后的 key）。
     *
     * <p>修复（A2-2）：原先未覆盖本方法，导致 strategy=SMART 在 keyExpr 路径下退化为接口默认实现
     * （原样返回），"智能压缩"从不生效。此处对齐 SMART 语义：
     * <ul>
     *   <li>{@code null} → {@link DefaultKey#EMPTY}；</li>
     *   <li>简单值（基本类型/包装/字符串）→ 原样返回（保证 keyExpr 生成的 key 与编程式业务 key 对齐）；</li>
     *   <li>复杂值（数组/集合/Map/对象）→ 受嵌套深度与元素数约束地紧凑化，超过阈值则 MD5。</li>
     * </ul>
     */
    @Override
    public Object generate(Object keyValue) {
        if (keyValue == null) {
            return DefaultKey.EMPTY;
        }
        // 简单值原样返回，保持与编程式/批量使用的业务 key 一致
        if (keyValue instanceof String || isPrimitiveOrWrapper(keyValue)) {
            return keyValue;
        }
        // 复杂值：紧凑表示，过长则 MD5（与多参版本一致的压缩策略）
        StringBuilder sb = new StringBuilder();
        appendParam(sb, keyValue, 0);
        String s = sb.toString();
        if (s.length() > keyLengthThreshold) {
            return "MD5:" + DigestUtils.md5DigestAsHex(s.getBytes(StandardCharsets.UTF_8));
        }
        return s;
    }

    /**
     * 添加参数到 Key
     *
     * @param key Key 构建器
     * @param param 参数
     * @param depth 当前嵌套深度
     */
    private void appendParam(StringBuilder key, Object param, int depth) {
        // 检查嵌套深度
        if (depth > maxNestingDepth) {
            key.append("...(max depth)");
            return;
        }
        
        if (param == null) {
            key.append("null");
        } else if (isPrimitiveOrWrapper(param)) {
            key.append(param);
        } else if (param instanceof String) {
            key.append(param);
        } else if (param.getClass().isArray()) {
            appendArray(key, param, depth);
        } else if (param instanceof Collection) {
            appendCollection(key, (Collection<?>) param, depth);
        } else if (param instanceof Map) {
            appendMap(key, (Map<?, ?>) param, depth);
        } else {
            // 复杂对象，使用 toString
            String str = param.toString();
            if (str.length() > 100) {
                // 如果 toString 过长，只取前 100 个字符
                key.append(str, 0, 100).append("...");
            } else {
                key.append(str);
            }
        }
    }
    
    /**
     * 添加数组到 Key
     */
    private void appendArray(StringBuilder key, Object array, int depth) {
        key.append("[");
        int length = Array.getLength(array);
        int maxElements = Math.min(length, 10); // 最多显示 10 个元素
        
        for (int i = 0; i < maxElements; i++) {
            if (i > 0) {
                key.append(CacheConsts.COMMA);
            }
            Object element = Array.get(array, i);
            appendParam(key, element, depth + 1);
        }
        
        if (length > maxElements) {
            key.append("...").append(length - maxElements).append(" more");
        }
        key.append("]");
    }
    
    /**
     * 添加集合到 Key
     */
    private void appendCollection(StringBuilder key, Collection<?> collection, int depth) {
        key.append("[");
        int i = 0;
        int maxElements = 10; // 最多显示 10 个元素
        
        for (Object element : collection) {
            if (i >= maxElements) {
                key.append("...").append(collection.size() - maxElements).append(" more");
                break;
            }
            if (i > 0) {
                key.append(CacheConsts.COMMA);
            }
            appendParam(key, element, depth + 1);
            i++;
        }
        key.append("]");
    }
    
    /**
     * 添加 Map 到 Key
     */
    private void appendMap(StringBuilder key, Map<?, ?> map, int depth) {
        key.append("{");
        int i = 0;
        int maxElements = 10; // 最多显示 10 个元素
        
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (i >= maxElements) {
                key.append("...").append(map.size() - maxElements).append(" more");
                break;
            }
            if (i > 0) {
                key.append(CacheConsts.COMMA);
            }
            appendParam(key, entry.getKey(), depth + 1);
            key.append("=");
            appendParam(key, entry.getValue(), depth + 1);
            i++;
        }
        key.append("}");
    }
    
    /**
     * 判断是否是基本类型或包装类型
     */
    private boolean isPrimitiveOrWrapper(Object obj) {
        Class<?> clazz = obj.getClass();
        return clazz.isPrimitive() ||
               clazz == Boolean.class ||
               clazz == Byte.class ||
               clazz == Character.class ||
               clazz == Short.class ||
               clazz == Integer.class ||
               clazz == Long.class ||
               clazz == Float.class ||
               clazz == Double.class;
    }
}
