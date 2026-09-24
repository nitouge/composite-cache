package io.github.nitouge.cache.annotation;

import io.github.nitouge.cache.core.consts.enums.CacheDataTypeEnum;
import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;

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
 *   <li>策略可配 - 方法级别控制回源策略</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 基础用法
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
 *
 * // 热点数据使用逻辑过期
 * @CacheAble(
 *     cacheName = "user",
 *     keyExpr = "#id",
 *     cacheL2 = @Cache_L2(
 *         TTL = 300,
 *         loadStrategy = RedisLoadStrategyEnum.LOGICAL_EXPIRE,
 *         logicalExpirePhysicalTtlFactor = 0  // 不设置物理TTL
 *     )
 * )
 * public User getHotUser(Long id) {
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
     * <p>Redis缓存的过期时间。
     *
     * <p>取值说明：
     * <ul>
     *   <li>正数 - 缓存过期时间，超过后自动删除</li>
     *   <li>-1 - 永不过期（所有策略都支持）</li>
     * </ul>
     *
     * <p>建议值：
     * <ul>
     *   <li>短期数据 - 300-3600秒（5分钟-1小时）</li>
     *   <li>中期数据 - 3600-86400秒（1小时-1天）</li>
     *   <li>长期数据 - 86400-604800秒（1天-7天）</li>
     *   <li>永久数据 - -1（永不过期，需谨慎使用）</li>
     * </ul>
     *
     * <p>注意：
     * <ul>
     *   <li>L2的TTL应该大于L1的TTL，保证L1过期后可以从L2回填</li>
     *   <li>使用 -1 时，需要通过 @CacheEvict 或其他方式主动清理过期数据</li>
     * </ul>
     *
     * @return 有效时间，默认300秒（5分钟）
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

    /**
     * Redis回源策略
     *
     * <p>用于在二级缓存未命中、需要回源加载数据时，选择不同的并发与一致性保护方式。
     *
     * <p>策略说明：
     * <ul>
     *   <li>{@link RedisLoadStrategyEnum#AUTO} - 自动模式，使用配置文件中的策略（默认）</li>
     *   <li>{@link RedisLoadStrategyEnum#NONE} - 无锁回源，性能最好，但可能缓存击穿</li>
     *   <li>{@link RedisLoadStrategyEnum#LOCK} - 分布式锁保护，强一致，避免击穿</li>
     *   <li>{@link RedisLoadStrategyEnum#LOGICAL_EXPIRE} - 逻辑过期，永不阻塞，适合热点数据</li>
     * </ul>
     *
     * <p>使用场景：
     * <ul>
     *   <li>热点数据 - 使用 LOGICAL_EXPIRE，保证高并发下的可用性</li>
     *   <li>普通数据 - 使用 LOCK，避免缓存击穿，节省资源</li>
     *   <li>临时数据 - 使用 NONE，允许短暂击穿，提高性能</li>
     * </ul>
     *
     * <p>优先级：注解显式指定 > CacheName配置 > 全局配置
     *
     * @return 回源策略，默认 AUTO（使用配置文件）
     * @see RedisLoadStrategyEnum
     */
    RedisLoadStrategyEnum loadStrategy() default RedisLoadStrategyEnum.AUTO;

    /**
     * 逻辑过期物理TTL倍数
     *
     * <p>仅在 {@link #loadStrategy()} 为 {@link RedisLoadStrategyEnum#LOGICAL_EXPIRE} 时生效。
     *
     * <p>取值说明：
     * <ul>
     *   <li>-1 - 使用全局配置（默认）</li>
     *   <li>0 - 不设置物理TTL，Redis key永不过期，完全依赖逻辑过期机制</li>
     *   <li>>= 1 - 物理TTL = 逻辑TTL × 该倍数，作为兜底保护</li>
     * </ul>
     *
     * <p>使用场景：
     * <ul>
     *   <li>核心热点数据 - 设置为 0，完全依赖逻辑过期和异步刷新</li>
     *   <li>普通热点数据 - 设置为 2-5，提供兜底保护，防止异步刷新失败导致脏数据永久存在</li>
     * </ul>
     *
     * @return 物理TTL倍数，默认 -1（使用全局配置）
     */
    int logicalExpirePhysicalTtlFactor() default -1;

}
