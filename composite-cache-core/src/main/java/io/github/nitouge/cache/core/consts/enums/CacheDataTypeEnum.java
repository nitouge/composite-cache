package io.github.nitouge.cache.core.consts.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * L2（Redis）二级缓存的底层数据结构类型。
 *
 * <p>当前仅 {@link #DATA_TYPE_STRING} 已实现（{@code RedissonRBucketCache}：每-key 独立 TTL、无热点、均匀分片，
 * 是"key→对象 + 每项独立过期"通用缓存的推荐结构）；{@link #DATA_TYPE_HASH} 为<b>规划中</b>的结构，
 * 暂未接入，配置后当前会回退到 String（见 {@code RedisCacheProvider}）。
 *
 */
@Getter
@AllArgsConstructor
@ToString
public enum CacheDataTypeEnum {

    /**
     * Redis 二级缓存 String 结构（默认、已实现、推荐）。
     */
    DATA_TYPE_STRING("Redis二级缓存String结构"),

    /**
     * Redis 二级缓存 Hash 结构（规划中，暂未实现；配置后当前回退 String）。
     */
    DATA_TYPE_HASH("Redis二级缓存Hash结构"),

    ;

    private final String desc;
}
