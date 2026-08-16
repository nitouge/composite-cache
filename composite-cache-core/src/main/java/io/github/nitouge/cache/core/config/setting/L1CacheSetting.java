package io.github.nitouge.cache.core.config.setting;

import io.github.nitouge.cache.core.consts.enums.CacheExpireModeEnum;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.concurrent.TimeUnit;

/**
 * 一级（本地，如 Caffeine/Guava）缓存的运行期设置。
 *
 */
@Data
@Accessors(chain = true)
public class L1CacheSetting {

    /**
     * 底层本地缓存的初始容量（哈希表初始大小），仅影响初始分配、非容量上限。
     */
    private int initialCapacity = 100;

    /**
     * 最大条目数，超出后按底层缓存的淘汰策略驱逐。
     */
    private long maximumSize = 500L;

    /**
     * 过期时间，单位由 {@link #expireTimeUnit} 决定。
     */
    private long expireTime = 300L;

    /**
     * {@link #expireTime} 的时间单位，默认秒。
     */
    private TimeUnit expireTimeUnit = TimeUnit.SECONDS;

    /**
     * 过期模式：{@link CacheExpireModeEnum#WRITE 写后过期}（默认）或 {@link CacheExpireModeEnum#ACCESS 访问后过期}。
     */
    private CacheExpireModeEnum cacheExpireModeEnum = CacheExpireModeEnum.WRITE;

    public L1CacheSetting() {
    }

    public L1CacheSetting(long expireTime, TimeUnit expireTimeUnit, CacheExpireModeEnum cacheExpireModeEnum) {
        this.expireTime = expireTime;
        this.expireTimeUnit = expireTimeUnit;
        this.cacheExpireModeEnum = cacheExpireModeEnum;
    }

    public L1CacheSetting(int initialCapacity, long maximumSize, long expireTime, TimeUnit expireTimeUnit, CacheExpireModeEnum cacheExpireModeEnum) {
        this.initialCapacity = initialCapacity;
        this.maximumSize = maximumSize;
        this.expireTime = expireTime;
        this.expireTimeUnit = expireTimeUnit;
        this.cacheExpireModeEnum = cacheExpireModeEnum;
    }
}
