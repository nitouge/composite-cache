package io.github.nitouge.cache.core.consts.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 缓存同步消息的操作类型，标识接收方应执行的动作。
 *
 * <p>{@link #EVICT} 删除指定 key 的缓存，{@link #CLEAR} 清空整个缓存，
 * {@link #REFRESH} 触发异步加载刷新。
 *
 */
@Getter
@AllArgsConstructor
@ToString
public enum CacheSyncTypeEnum {

    /**
     * 删除缓存
     */
    EVICT("删除缓存"),

    /**
     * 清空缓存
     */
    CLEAR("清空缓存"),

    /**
     * 异步刷新
     */
    REFRESH("异步加载刷新");

    private final String label;

    public static CacheSyncTypeEnum getCacheSyncTypeEnum(String syncType) {
        for (CacheSyncTypeEnum cacheSyncTypeEnum : CacheSyncTypeEnum.values()) {
            if (cacheSyncTypeEnum.name().equals(syncType)) {
                return cacheSyncTypeEnum;
            }
        }
        throw new IllegalArgumentException("非法参数，无法找到参数【" + syncType + "】对应的缓存同步类型");
    }

}
