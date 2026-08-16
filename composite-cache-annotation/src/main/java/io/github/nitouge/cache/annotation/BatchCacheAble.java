package io.github.nitouge.cache.annotation;

import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;

import java.lang.annotation.*;

/**
 * 批量缓存注解
 * 
 * <p>用于批量查询场景，自动处理批量缓存查询和回填
 *
 * <p>使用示例：
 * <pre>{@code
 * @BatchCacheAble(
 *     cacheName = "user",
 *     keyExtractor = "#user.id"
 * )
 * public List<User> getUsersByIds(List<Long> ids) {
 *     return userRepository.findByIds(ids);
 * }
 * }</pre>
 *
 * <p>执行流程：
 * <ol>
 *   <li>根据输入的 ID 列表批量查询缓存</li>
 *   <li>找出未命中的 ID</li>
 *   <li>只用未命中的 ID 调用方法查询数据库</li>
 *   <li>将查询结果批量写入缓存</li>
 *   <li>合并缓存和数据库结果，按原始顺序返回</li>
 * </ol>
 * 
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface BatchCacheAble {
    
    /**
     * 缓存名称
     * 
     * @return 缓存名称
     */
    String cacheName();
    
    /**
     * Key 提取器表达式（SpEL）
     * <p>从返回的对象中提取 Key
     * 
     * <p>示例：
     * <ul>
     *   <li>#user.id - 从 User 对象提取 id</li>
     *   <li>#product.productId - 从 Product 对象提取 productId</li>
     *   <li>#order.orderId - 从 Order 对象提取 orderId</li>
     * </ul>
     *
     * @return SpEL 表达式
     */
    String keyExtractor();
    
    /**
     * 输入参数名称（用于获取查询的 ID 列表）
     * <p>默认为空，表示使用第一个参数
     * 
     * @return 参数名称
     */
    String inputParam() default "";
    
    /**
     * 是否返回 null 值的 Key
     * <p>true：返回所有 Key（包括值为 null 的）
     * <p>false：只返回有值的 Key
     * 
     * @return 默认 false
     */
    boolean returnNullValueKey() default false;
    
    /**
     * 缓存模式
     * 
     * @return 缓存模式
     */
    CacheModeEnum cacheMode() default CacheModeEnum.L1_L2;

    /**
     * 是否忽略缓存操作异常
     * 
     * <p>true：缓存操作失败时，降级到直接执行方法（推荐）
     * <p>false：缓存操作失败时，抛出异常
     * 
     * @return 默认 true
     */
    boolean ignoreException() default true;
    
    /**
     * 一级缓存配置
     * 
     * @return L1 缓存配置
     */
    Cache_L1 cacheL1() default @Cache_L1;

    /**
     * 二级缓存配置
     *
     * @return L2 缓存配置
     */
    Cache_L2 cacheL2() default @Cache_L2;
}
