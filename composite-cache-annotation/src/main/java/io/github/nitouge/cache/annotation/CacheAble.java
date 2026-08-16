package io.github.nitouge.cache.annotation;

import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 缓存查询注解
 * 
 * <p>用于方法级别的缓存查询，支持单级（L1/L2）和双级（L1+L2）缓存模式。
 * 
 * <h3>核心功能</h3>
 * <ul>
 *   <li>缓存命中 - 直接返回缓存值，不执行方法</li>
 *   <li>缓存未命中 - 执行方法并将结果缓存</li>
 *   <li>异常降级 - 缓存操作失败时可降级到直接执行方法</li>
 *   <li>灵活配置 - 支持 L1/L2 独立配置或组合配置</li>
 * </ul>
 * 
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 基础用法
 * @CacheAble(
 *     cacheName = "user",
 *     keyExpr = "#id"
 * )
 * public User getUserById(Long id) {
 *     return userMapper.selectById(id);
 * }
 * 
 * // 自定义 L1+L2 配置
 * @CacheAble(
 *     cacheName = "product",
 *     keyExpr = "#productId",
 *     cacheMode = CacheModeEnum.L1_L2,
 *     cacheL1 = @Cache_L1(TTL = 300, maximumSize = 500),
 *     cacheL2 = @Cache_L2(TTL = 3600)
 * )
 * public Product getProduct(Long productId) {
 *     return productMapper.selectById(productId);
 * }
 * 
 * // 只使用 L2 缓存（Redis）
 * @CacheAble(
 *     cacheName = "order",
 *     keyExpr = "#orderId",
 *     cacheMode = CacheModeEnum.L2
 * )
 * public Order getOrder(Long orderId) {
 *     return orderMapper.selectById(orderId);
 * }
 * }</pre>
 * 
 * <h3>执行流程</h3>
 * <pre>
 * 1. 根据 keyExpr 生成缓存 Key
 * 2. 查询缓存（ L1 -> L2 或单级）
 * 3. 命中：直接返回缓存值
 * 4. 未命中：执行方法 -> 缓存结果 -> 返回
 * </pre>
 * 
 * @see CachePut
 * @see CacheEvict
 * @see BatchCacheAble
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface CacheAble {

    /**
     * 缓存名称（必填）
     * 
     * <p>用于标识缓存空间，不同的 cacheName 对应不同的缓存实例。
     * 
     * <p>示例："user", "product", "order"
     * 
     * @return 缓存名称
     */
    String cacheName();

    /**
     * 缓存模式
     * 
     * <p>支持三种模式：
     * <ul>
     *   <li>{@link CacheModeEnum#L1} - 只使用本地缓存（Caffeine）</li>
     *   <li>{@link CacheModeEnum#L2} - 只使用远程缓存（Redis）</li>
     *   <li>{@link CacheModeEnum#L1_L2} - 使用双级缓存（默认）</li>
     * </ul>
     * 
     * @return 缓存模式，默认 L1_L2
     */
    CacheModeEnum cacheMode() default CacheModeEnum.L1_L2;

    /**
     * 缓存 Key 表达式（必填，SpEL 表达式）
     * 
     * <p>使用 SpEL 表达式从方法参数中提取缓存 Key。
     * 
     * <p>支持的表达式：
     * <ul>
     *   <li>#参数名 - 直接使用参数：{@code #id}</li>
     *   <li>#参数名.属性 - 使用对象属性：{@code #user.id}</li>
     *   <li>字符串拼接 - {@code 'user:' + #id}</li>
     *   <li>复杂表达式 - {@code #user.type + ':' + #user.id}</li>
     * </ul>
     * 
     * <p>示例：
     * <pre>
     * keyExpr = "#id"              // 使用id参数
     * keyExpr = "#user.userId"     // 使用user对象的userId属性
     * keyExpr = "'user:' + #id"    // 拼接字符串
     * </pre>
     * 
     * @return SpEL表达式
     */
    String keyExpr() default "";

    /**
     * 是否忽略缓存操作异常（容错降级）
     * 
     * <p>true：缓存操作失败时，降级到直接执行方法，保证业务不中断（推荐）
     * <p>false：缓存操作失败时，抛出异常，中断业务流程
     * 
     * <p>适用场景：
     * <ul>
     *   <li>true - 生产环境推荐，缓存故障不影响业务</li>
     *   <li>false - 开发/测试环境，快速发现缓存配置问题</li>
     * </ul>
     * 
     * @return 默认true
     */
    boolean ignoreException() default true;

    /**
     * 一级缓存（本地缓存）配置
     * 
     * <p>基于Caffeine实现，提供高性能的本地缓存。
     * 
     * <p>配置项：
     * <ul>
     *   <li>initialCapacity - 初始容量</li>
     *   <li>maximumSize - 最大容量</li>
     *   <li>TTL - 过期时间</li>
     *   <li>expireMode - 过期模式（写入后过期/访问后过期）</li>
     * </ul>
     * 
     * @return L1缓存配置
     * @see Cache_L1
     */
    Cache_L1 cacheL1() default @Cache_L1;

    /**
     * 二级缓存（远程缓存）配置
     * 
     * <p>基于Redis实现，提供分布式缓存能力。
     * 
     * <p>配置项：
     * <ul>
     *   <li>TTL - 过期时间</li>
     *   <li>dataType - 数据类型（String 已实现；Hash 规划中）</li>
     * </ul>
     * 
     * @return L2缓存配置
     * @see Cache_L2
     */
    Cache_L2 cacheL2() default @Cache_L2;

}
