package io.github.nitouge.cache.core.wrapper;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

/**
 * 逻辑过期包装器。
 *
 * <p>用于 {@link io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum#LOGICAL_EXPIRE} 策略：
 * 真实数据外包一层逻辑过期时间，Redis 物理上不（或很晚）过期。读到逻辑过期的值时返回旧值并触发异步刷新，
 * 从而"永不阻塞、永不击穿"。
 *
 */
@Setter
@Getter
public class LogicalExpireWrapper implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实际存储的值（可能是业务对象，也可能是 NullValueWrapper）
     */
    private Object data;

    /**
     * 逻辑过期时间戳（毫秒）
     */
    private long logicalExpireAt;

    public LogicalExpireWrapper() {
    }

    public LogicalExpireWrapper(Object data, long logicalExpireAt) {
        this.data = data;
        this.logicalExpireAt = logicalExpireAt;
    }

    /**
     * 是否已逻辑过期。
     *
     * @param nowMillis 当前时间戳（毫秒），便于测试注入
     */
    public boolean isExpired(long nowMillis) {
        return nowMillis > logicalExpireAt;
    }

    public boolean isExpired() {
        return isExpired(System.currentTimeMillis());
    }

}
