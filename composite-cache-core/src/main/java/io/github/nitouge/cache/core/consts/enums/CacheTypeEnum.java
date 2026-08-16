package io.github.nitouge.cache.core.consts.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 缓存的具体实现类型。
 *
 * <p>{@link #CAFFEINE}、{@link #GUAVA} 为本地一级缓存（L1）实现，
 * {@link #REDIS} 为远程二级缓存（L2）实现。
 *
 */
@Getter
@AllArgsConstructor
@ToString
public enum CacheTypeEnum {

    /**
     * Caffeine缓存（L1）
     */
    CAFFEINE,
    
    /**
     * Guava缓存（L1）
     */
    GUAVA,
    
    /**
     * Redis缓存（L2）
     */
    REDIS,
    
    ;

    /**
     * 根据缓存类型名称获取枚举
     * 
     * @param cacheType 缓存类型名称
     * @return 缓存类型枚举
     * @throws IllegalArgumentException 如果找不到对应的缓存类型
     */
    public static CacheTypeEnum getCacheTypeEnum(String cacheType) {
        for (CacheTypeEnum modeEnum : CacheTypeEnum.values()) {
            if (modeEnum.name().equals(cacheType)) {
                return modeEnum;
            }
        }
        throw new IllegalArgumentException("非法参数，无法找到参数【" + cacheType + "】对应的缓存类型");
    }
    
    /**
     * 获取所有缓存类型
     * 
     * @return 所有缓存类型的数组
     */
    public static CacheTypeEnum[] getAllTypes() {
        return CacheTypeEnum.values();
    }

}
