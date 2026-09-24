package io.github.nitouge.cache.core.config.setting;

import io.github.nitouge.cache.core.consts.enums.CacheDataTypeEnum;
import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.concurrent.TimeUnit;

/**
 * 二级（Redis）缓存的运行期设置。
 *
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class L2CacheSetting {

    /**
     * Redis 底层数据结构类型，默认 {@link CacheDataTypeEnum#DATA_TYPE_STRING String}。
     */
    private CacheDataTypeEnum dataType = CacheDataTypeEnum.DATA_TYPE_STRING;

    /**
     * 过期时间，单位由 {@link #expireTimeUnit} 决定；默认 {@code -1} 表示不设 TTL（写入后永不过期）。
     *
     * <p>实际写入时小于等于 0 的值会被归一化为"不过期"。
     */
    private long expireTime = -1L;

    /**
     * {@link #expireTime} 的时间单位，默认秒。
     */
    private TimeUnit expireTimeUnit = TimeUnit.SECONDS;

    /**
     * Redis 回源策略（注解级别配置）。
     *
     * <p>为 null 或 AUTO 时，使用 CacheName 级别或全局配置。
     * <p>优先级：注解显式指定 > CacheName 配置 > 全局配置。
     */
    private RedisLoadStrategyEnum loadStrategy;

    /**
     * 逻辑过期物理 TTL 倍数（注解级别配置）。
     *
     * <p>为 null 或 < 0 时，使用全局配置。
     * <p>0: 不设置物理 TTL
     * <p>>= 1: 物理 TTL = 逻辑 TTL × 该倍数
     */
    private Integer logicalExpirePhysicalTtlFactor;

}
