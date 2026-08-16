package io.github.nitouge.cache.annotation.key;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Arrays;

/**
 * 默认缓存 Key
 *
 * <p>用于封装多个参数或数组参数作为缓存 Key。
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>实现 Serializable，可序列化</li>
 *   <li>重写 equals 和 hashCode，支持正确的比较</li>
 *   <li>使用深度比较，支持数组参数</li>
 *   <li>提供空实例 {@link #EMPTY}</li>
 * </ul>
 * 
 * <h3>使用场景</h3>
 * <pre>{@code
 * // 多个参数
 * DefaultKey key1 = new DefaultKey("user", 123, "active");
 * 
 * // 数组参数
 * DefaultKey key2 = new DefaultKey(new int[]{1, 2, 3});
 * 
 * // 空参数
 * DefaultKey key3 = DefaultKey.EMPTY;
 * }</pre>
 * 
 */
public class DefaultKey implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 空实例（用于无参数的情况）
     */
    public static final DefaultKey EMPTY = new DefaultKey();
    
    /**
     * 参数数组
     */
    private final Object[] params;
    
    /**
     * 预计算的 hashCode
     */
    private final int hashCode;

    /**
     * 构造函数
     *
     * @param elements 参数数组
     */
    public DefaultKey(Object... elements) {
        Assert.notNull(elements, "params must not be null");
        this.params = elements.clone();
        this.hashCode = Arrays.deepHashCode(this.params);
    }

    /**
     * 比较两个 DefaultKey 是否相等
     *
     * <p>使用深度比较，支持数组参数。
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DefaultKey)) {
            return false;
        }
        DefaultKey other = (DefaultKey) obj;
        return Arrays.deepEquals(this.params, other.params);
    }

    /**
     * 返回 hashCode
     *
     * <p>使用深度 hashCode，支持数组参数。
     */
    @Override
    public int hashCode() {
        return this.hashCode;
    }
    
    /**
     * 返回字符串表示
     * 
     * <p>格式：{@code DefaultKey [param1, param2, ...]}
     */
    @Override
    public String toString() {
        return this.getClass().getSimpleName() + " [" + 
               StringUtils.arrayToCommaDelimitedString(this.params) + "]";
    }
    
    /**
     * 获取参数数组（返回副本）
     * 
     * @return 参数数组的副本
     */
    public Object[] getParams() {
        return this.params.clone();
    }
    
    /**
     * 获取参数数量
     * 
     * @return 参数数量
     */
    public int getParamCount() {
        return this.params.length;
    }
}
