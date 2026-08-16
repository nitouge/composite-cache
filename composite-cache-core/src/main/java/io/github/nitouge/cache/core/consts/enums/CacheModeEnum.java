package io.github.nitouge.cache.core.consts.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 缓存模式，决定数据落在哪些层级。
 *
 * <p>{@link #L1} 仅使用本地一级缓存，{@link #L2} 仅使用远程二级缓存，
 * {@link #L1_L2} 为本地 + 远程组合的多级缓存。
 *
 */
@Getter
@AllArgsConstructor
@ToString
public enum CacheModeEnum {

    /**
     * 一级缓存
     */
    L1("一级缓存"),

    /**
     * 二级缓存
     */
    L2("二级缓存"),

    /**
     * 一级缓存和二级缓存组合
     */
    L1_L2("一级缓存和二级缓存组合"),

    ;

    private String desc;

    /**
     * 根据缓存模式名称获取枚举
     * 
     * @param cacheMode 缓存模式名称
     * @return 缓存模式枚举
     * @throws IllegalArgumentException 如果找不到对应的缓存模式
     */
    public static CacheModeEnum getCacheModeEnum(String cacheMode) {
        for (CacheModeEnum modeEnum : CacheModeEnum.values()) {
            if (modeEnum.name().equals(cacheMode)) {
                return modeEnum;
            }
        }
        throw new IllegalArgumentException("非法参数，无法找到参数【" + cacheMode + "】对应的缓存模式");
    }
    
    /**
     * 获取所有缓存模式
     * 
     * @return 所有缓存模式的数组
     */
    public static CacheModeEnum[] getAllModes() {
        return CacheModeEnum.values();
    }

}
