package io.github.nitouge.cache.annotation;

import io.github.nitouge.cache.core.consts.enums.CacheExpireModeEnum;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * 一级缓存（本地缓存）配置注解
 * 
 * <p>基于 Caffeine 实现的高性能本地缓存配置。
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>高性能 - 基于 Caffeine，提供极高的读写性能</li>
 *   <li>容量控制 - 支持最大容量限制，自动淘汰</li>
 *   <li>过期策略 - 支持写入后过期和访问后过期</li>
 *   <li>线程安全 - 内置并发控制</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @CacheAble(
 *     cacheName = "user",
 *     keyExpr = "#id",
 *     cacheL1 = @Cache_L1(
 *         initialCapacity = 100,
 *         maximumSize = 1000,
 *         TTL = 300,
 *         timeUnit = TimeUnit.SECONDS,
 *         expireMode = CacheExpireModeEnum.WRITE
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
public @interface Cache_L1 {

    /**
     * 缓存初始容量
     *
     * <p>设置缓存的初始大小，合理设置可减少扩容次数。
     *
     * <p>建议值：根据预期缓存数据量设置，一般为 maximumSize 的 10%-20%。
     *
     * @return 初始容量，默认 100
     */
    int initialCapacity() default 100;

    /**
     * 缓存最大容量
     *
     * <p>设置缓存的最大条目数，超过后会根据 LRU 策略淘汰。
     * 
     * <p>建议值：
     * <ul>
     *   <li>热点数据 - 500-2000</li>
     *   <li>一般数据 - 1000-5000</li>
     *   <li>大量数据 - 5000-10000</li>
     * </ul>
     * 
     * <p>注意：过大会占用过多内存，过小会降低命中率。
     * 
     * @return 最大容量，默认 1000
     */
    int maximumSize() default 1000;

    /**
     * 缓存有效时间（Time To Live）
     *
     * <p>结合 timeUnit 和 expireMode 使用：
     * <ul>
     *   <li>expireMode=WRITE - 写入后经过 TTL 时间过期</li>
     *   <li>expireMode=ACCESS - 最后访问后经过 TTL 时间过期</li>
     * </ul>
     *
     * <p>建议值：
     * <ul>
     *   <li>热点数据 - 300-600 秒（5-10 分钟）</li>
     *   <li>一般数据 - 600-3600 秒（10 分钟-1 小时）</li>
     *   <li>冷数据 - 3600-7200 秒（1-2 小时）</li>
     * </ul>
     *
     * @return 有效时间，默认 300 秒（5 分钟）
     */
    int TTL() default 300;

    /**
     * 缓存时间单位
     *
     * <p>配合 TTL 使用，指定 TTL 的时间单位。
     *
     * @return 时间单位，默认 SECONDS（秒）
     * @see TimeUnit
     */
    TimeUnit timeUnit() default TimeUnit.SECONDS;

    /**
     * 缓存过期模式
     *
     * <p>支持两种模式：
     * <ul>
     *   <li>{@link CacheExpireModeEnum#WRITE} - 写入后过期：从写入时间开始计算 TTL（默认）</li>
     *   <li>{@link CacheExpireModeEnum#ACCESS} - 访问后过期：从最后访问时间开始计算 TTL</li>
     * </ul>
     *
     * <h3>使用场景</h3>
     * <ul>
     *   <li>WRITE - 适用于数据更新频繁的场景，保证数据新鲜度</li>
     *   <li>ACCESS - 适用于热点数据，经常访问的数据不会过期</li>
     * </ul>
     *
     * @return 过期模式，默认 WRITE
     * @see CacheExpireModeEnum
     */
    CacheExpireModeEnum expireMode() default CacheExpireModeEnum.WRITE;
}
