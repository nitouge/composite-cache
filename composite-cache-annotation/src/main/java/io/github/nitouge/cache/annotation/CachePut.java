package io.github.nitouge.cache.annotation;

import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 缓存更新注解
 * 
 * <p>用于更新缓存数据，无论缓存是否存在，都会执行方法并更新缓存。
 * 
 * <h3>核心功能</h3>
 * <ul>
 *   <li>强制执行 - 总是执行方法，不查询缓存</li>
 *   <li>更新缓存 - 将方法返回值写入缓存</li>
 *   <li>支持#result - 可使用#result引用方法返回值</li>
 *   <li>多级更新 - 支持同时更新L1和L2缓存</li>
 * </ul>
 * 
 * <h3>使用场景</h3>
 * <ul>
 *   <li>数据更新后同步缓存</li>
 *   <li>定时刷新缓存</li>
 *   <li>预热缓存</li>
 * </ul>
 * 
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 基础用法：更新后同步缓存
 * @CachePut(
 *     cacheName = "user",
 *     keyExpr = "#user.id"
 * )
 * public User updateUser(User user) {
 *     userMapper.update(user);
 *     return user;
 * }
 * 
 * // 使用#result引用返回值
 * @CachePut(
 *     cacheName = "product",
 *     keyExpr = "#result.productId"
 * )
 * public Product createProduct(ProductDTO dto) {
 *     Product product = convertToEntity(dto);
 *     productMapper.insert(product);
 *     return product;
 * }
 * 
 * // 预热缓存
 * @CachePut(
 *     cacheName = "hotData",
 *     keyExpr = "#key"
 * )
 * public Data preloadCache(String key) {
 *     return dataService.loadFromDB(key);
 * }
 * }</pre>
 * 
 * <h3>执行流程</h3>
 * <pre>
 * 1. 执行方法（不查询缓存）
 * 2. 根据keyExpr生成Key
 * 3. 将方法返回值写入缓存
 * 4. 返回方法结果
 * </pre>
 * 
 * <h3>与@CacheAble的区别</h3>
 * <ul>
 *   <li>@CacheAble - 查询缓存，命中则不执行方法</li>
 *   <li>@CachePut - 总是执行方法，然后更新缓存</li>
 * </ul>
 * 
 * @see CacheAble
 * @see CacheEvict
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface CachePut {

    /**
     * 缓存名称（必填）
     * 
     * @return 缓存名称
     */
    String cacheName();

    /**
     * 缓存模式
     * 
     * @return 缓存模式，默认L1_L2
     */
    CacheModeEnum cacheMode() default CacheModeEnum.L1_L2;

    /**
     * 缓存Key表达式（必填，SpEL表达式）
     * 
     * <p>支持使用#result引用方法返回值。
     * 
     * <p>示例：
     * <pre>
     * keyExpr = "#user.id"         // 使用参数
     * keyExpr = "#result.userId"   // 使用返回值
     * </pre>
     * 
     * @return SpEL表达式
     */
    String keyExpr() default "";

    /**
     * 是否忽略更新异常
     * 
     * @return 默认true
     */
    boolean ignoreException() default true;

    /**
     * 一级缓存配置
     * 
     * @return L1缓存配置
     */
    Cache_L1 cacheL1() default @Cache_L1;

    /**
     * 二级缓存配置
     * 
     * @return L2缓存配置
     */
    Cache_L2 cacheL2() default @Cache_L2;

}
