package io.github.nitouge.cache.annotation;

import io.github.nitouge.cache.core.consts.enums.CacheDataTypeEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 二级缓存（远程缓存）配置注解
 * 
 * <p>基于Redis实现的分布式缓存配置。
 * 
 * <h3>特性</h3>
 * <ul>
 *   <li>分布式 - 支持多实例共享缓存</li>
 *   <li>持久化 - 数据存储在Redis，重启不丢失</li>
 *   <li>数据类型 - String（已实现、推荐）/Hash（规划中，暂回退 String）</li>
 *   <li>容量大 - 不受本地内存限制</li>
 * </ul>
 * 
 * <h3>使用示例</h3>
 * <pre>{@code
 * @CacheAble(
 *     cacheName = "user",
 *     keyExpr = "#id",
 *     cacheL2 = @Cache_L2(
 *         TTL = 86400,
 *         timeUnit = TimeUnit.SECONDS,
 *         dataType = CacheDataTypeEnum.DATA_TYPE_STRING
 *     )
 * )
 * public User getUserById(Long id) {
 *     return userMapper.selectById(id);
 * }
 * }</pre>
 * 
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface Cache_L2 {
    /**
     * Redis数据类型
     * 
     * <p>支持的数据类型：
     * <ul>
     *   <li>{@link CacheDataTypeEnum#DATA_TYPE_STRING} - String类型（默认、已实现、推荐）</li>
     *   <li>{@link CacheDataTypeEnum#DATA_TYPE_HASH} - Hash类型（规划中，暂未实现，配置后回退 String）</li>
     * </ul>
     *
     * <p>选择建议：
     * <ul>
     *   <li>String - 适用于"key→对象 + 每项独立过期"的通用缓存（最常用、推荐）</li>
     *   <li>Hash - 适用于一组小字段、统一过期、量可控且非热点的场景（待实现）</li>
     * </ul>
     * 
     * @return 数据类型，默认String
     */
    CacheDataTypeEnum dataType() default CacheDataTypeEnum.DATA_TYPE_STRING;

    /**
     * 缓存有效时间（Time To Live）
     * 
     * <p>Redis缓存的过期时间，超过后自动删除。
     * 
     * <p>建议值：
     * <ul>
     *   <li>短期数据 - 300-3600秒（5分钟-1小时）</li>
     *   <li>中期数据 - 3600-86400秒（1小时-1天）</li>
     *   <li>长期数据 - 86400-604800秒（1天-7天）</li>
     * </ul>
     * 
     * <p>注意：L2的TTL应该大于L1的TTL，保证L1过期后可以从L2回填。
     * 
     * @return 有效时间，默认86400秒（1天）
     */
    long TTL() default 300L;


    /**
     * 缓存时间单位
     * 
     * <p>配合TTL使用，指定TTL的时间单位。
     * 
     * @return 时间单位，默认SECONDS（秒）
     * @see TimeUnit
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

}
