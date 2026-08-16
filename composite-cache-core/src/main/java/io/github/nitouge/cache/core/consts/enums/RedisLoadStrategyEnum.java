package io.github.nitouge.cache.core.consts.enums;

/**
 * L2（Redis）缓存失效后回源加载的策略。
 *
 * <p>用于在二级缓存未命中、需要回源加载数据时，选择不同的并发与一致性保护方式。
 *
 */
public enum RedisLoadStrategyEnum {

    /**
     * 不加保护：未命中直接回源加载并写回。
     * <p>性能最好，但集群高并发下同一 key 可能有多次回源（缓存击穿）。
     * <p>注：本地 L1 若为 LoadingCache，仍提供单机维度的单飞保护。
     */
    NONE,

    /**
     * 分布式锁：回源前抢分布式锁，保证集群范围同一时刻只有一个加载者。
     * <p>{@code tryLock=true} 时其余请求快速失败（抛 RedisTrylockFailException）；
     * {@code tryLock=false} 时其余请求阻塞等待。适合写少读多、强一致优先的场景。
     */
    LOCK,

    /**
     * 逻辑过期：缓存值携带逻辑过期时间，物理上不（或很晚）过期。
     * <p>读到逻辑过期的值时返回旧值并异步刷新，保证"永不阻塞、永不击穿"，
     * 代价是可能短暂读到旧数据。适合高并发读、可容忍短暂不一致的热点场景。
     */
    LOGICAL_EXPIRE
}
