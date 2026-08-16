package io.github.nitouge.cache.core.consts.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 一级缓存（L1）的过期模式。
 *
 * <p>{@link #WRITE} 为写后过期，有效时间从最后一次写入开始计算；{@link #ACCESS} 为访问后过期，
 * 每次读取都会刷新有效时间，常用于实现"长时间不访问才淘汰"的活跃数据缓存。
 *
 */
@Getter
@AllArgsConstructor
@ToString
public enum CacheExpireModeEnum {

    /**
     * 每写入一次重新计算一次缓存的有效时间
     */
    WRITE("计算到期时间的标准是:距离最后一次写入时间到期时失效"),

    /**
     * 每访问一次重新计算一次缓存的有效时间
     */
    ACCESS("计算到期时间的标准是:距离最后一次访问时间到期时失效");

    private String desc;

}
