package io.github.nitouge.cache.annotation;


import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 缓存删除注解
 * 
 * <p>用于删除缓存数据，支持单个Key删除或清空整个缓存空间。
 * 
 * <h3>核心功能</h3>
 * <ul>
 *   <li>单Key删除 - 根据keyExpr删除指定缓存</li>
 *   <li>全部删除 - removeAll=true时清空整个缓存空间</li>
 *   <li>多级删除 - 支持同时删除L1和L2缓存</li>
 * </ul>
 * 
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 删除单个缓存
 * @CacheEvict(
 *     cacheName = "user",
 *     keyExpr = "#id"
 * )
 * public void deleteUser(Long id) {
 *     userMapper.deleteById(id);
 * }
 * 
 * // 清空整个缓存空间
 * @CacheEvict(
 *     cacheName = "user",
 *     removeAll = true
 * )
 * public void deleteAllUsers() {
 *     userMapper.deleteAll();
 * }
 * }</pre>
 * 
 * <h3>执行流程</h3>
 * <pre>
 * 1. 根据removeAll判断删除模式
 * 2. removeAll=false: 根据keyExpr生成Key并删除
 * 3. removeAll=true: 清空整个缓存空间
 * 4. 执行原方法
 * </pre>
 * 
 * @see CacheAble
 * @see CachePut
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface CacheEvict {

    /**
     * 缓存名称（必填）
     * 
     * @return 缓存名称
     */
    String cacheName();

    /**
     * 缓存模式
     * 
     * <p>指定删除哪些级别的缓存。
     * 
     * @return 缓存模式，默认L1_L2（同时删除L1和L2）
     */
    CacheModeEnum cacheMode() default CacheModeEnum.L1_L2;

    /**
     * 是否删除所有缓存
     * 
     * <p>true：清空整个缓存空间（忽略keyExpr）
     * <p>false：只删除keyExpr指定的缓存（默认）
     * 
     * @return 默认false
     */
    boolean removeAll() default false;

    /**
     * 缓存Key表达式（SpEL表达式）
     * 
     * <p>当removeAll=false时必填。
     * 
     * @return SpEL表达式
     */
    String keyExpr() default "";

    /**
     * 是否忽略删除异常
     * 
     * <p>true：删除失败时不影响业务（推荐）
     * <p>false：删除失败时抛出异常
     * 
     * @return 默认true
     */
    boolean ignoreException() default true;
}
