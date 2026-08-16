package io.github.nitouge.cache.core.config.setting;

import io.github.nitouge.cache.core.consts.enums.CacheDataTypeEnum;
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

}
