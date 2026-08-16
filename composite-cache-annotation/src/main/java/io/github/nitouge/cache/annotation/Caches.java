package io.github.nitouge.cache.annotation;


import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 组合缓存注解
 * 
 * <p>用于在同一个方法上同时使用多个缓存注解。
 * 
 * <h3>核心功能</h3>
 * <ul>
 *   <li>多注解组合 - 支持同时使用@CacheAble、@CachePut、@CacheEvict</li>
 *   <li>执行顺序 - 按照Evict -> Put -> Able的顺序执行</li>
 *   <li>多缓存空间 - 支持同时操作多个缓存空间</li>
 * </ul>
 * 
 * <h3>使用场景</h3>
 * <ul>
 *   <li>更新后同步多个缓存</li>
 *   <li>删除旧缓存后更新新缓存</li>
 *   <li>同时维护主缓存和关联缓存</li>
 * </ul>
 * 
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 更新用户后，同时更新多个缓存
 * @Caches(
 *     cachePut = {
 *         @CachePut(cacheName = "user", keyExpr = "#user.id"),
 *         @CachePut(cacheName = "userByName", keyExpr = "#user.name")
 *     }
 * )
 * public User updateUser(User user) {
 *     userMapper.update(user);
 *     return user;
 * }
 * 
 * // 删除旧缓存，然后更新新缓存
 * @Caches(
 *     cacheEvict = {
 *         @CacheEvict(cacheName = "oldCache", keyExpr = "#id")
 *     },
 *     cachePut = {
 *         @CachePut(cacheName = "newCache", keyExpr = "#result.id")
 *     }
 * )
 * public User migrateUser(Long id) {
 *     User user = userMapper.selectById(id);
 *     // ... 迁移逻辑
 *     return user;
 * }
 * }</pre>
 * 
 * <h3>执行顺序</h3>
 * <pre>
 * 1. 执行所有@CacheEvict注解
 * 2. 执行所有@CachePut注解
 * 3. 执行所有@CacheAble注解
 * 4. 如果都没有，直接执行方法
 * </pre>
 * 
 * @see CacheAble
 * @see CachePut
 * @see CacheEvict
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface Caches {

    /**
     * {@code @CacheAble注解数组}
     * 
     * @return CacheAble注解数组
     */
    CacheAble[] cacheAble() default {};

    /**
     * {@code @CachePut注解数组}
     * 
     * @return CachePut注解数组
     */
    CachePut[] cachePut() default {};

    /**
     * {@code @CacheEvict注解数组}
     * 
     * @return CacheEvict注解数组
     */
    CacheEvict[] cacheEvict() default {};
}
