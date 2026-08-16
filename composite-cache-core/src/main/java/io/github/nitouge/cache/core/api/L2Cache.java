package io.github.nitouge.cache.core.api;

import java.util.concurrent.TimeUnit;

/**
 * L2（分布式 / Redis）缓存接口。
 *
 * <p>承担多级缓存的远端层：在 {@link Cache} 基础上额外暴露物理 key 拼装与物理 TTL，
 * 供组合缓存层在写入/读取远端存储时使用。
 *
 * @see L1Cache 一级缓存（本地缓存）
 */
public interface L2Cache extends Cache {

    /**
     * 将业务 key 拼装为底层存储的物理 key（通常拼接 cacheName 等前缀，保证维度隔离）。
     */
    Object buildKey(Object key);

    /**
     * L2 物理 TTL 数值，与 {@link #getExpireTimeUnit()} 配合表示远端存储的过期时长。
     */
    long getExpireTime();

    /**
     * {@link #getExpireTime()} 对应的时间单位。
     */
    TimeUnit getExpireTimeUnit();
}
