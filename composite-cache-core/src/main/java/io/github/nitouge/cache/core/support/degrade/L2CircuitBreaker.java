package io.github.nitouge.cache.core.support.degrade;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * L2（Redis）运行期降级熔断器。
 *
 * <p>用于在 Redis 抖动/不可用时保护应用：连续失败达阈值后<b>短路</b>，让调用方快速降级（仅 L1 + 回源 DB），
 * 而不是每个请求都卡在 Redis 超时上；Redis 恢复后自动闭合。
 *
 * <h3>状态机</h3>
 * <pre>
 * CLOSED  正常放行；连续失败计数达 failureThreshold → OPEN
 * OPEN    短路（不打 Redis），直接降级；经过 openMillis → 进入 HALF_OPEN
 * HALF_OPEN 只放行<b>一个</b>试探请求：成功 → CLOSED（恢复），失败 → OPEN（重新计时）
 * </pre>
 *
 * <p>线程安全。{@code enabled=false} 时为透明直通（{@link #allowRequest()} 恒为 true，回调为空操作），
 * 保证未开启降级时零行为变化。
 *
 */
@Slf4j
public class L2CircuitBreaker {

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    private final boolean enabled;

    private final int failureThreshold;

    private final long openMillis;

    private volatile State state = State.CLOSED;

    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    private final AtomicLong openedAt = new AtomicLong(0L);

    /**
     * HALF_OPEN（或 OPEN 窗口刚到）下只允许一个试探请求在途，避免恢复探测时的并发冲击。
     */
    private final AtomicBoolean trialInFlight = new AtomicBoolean(false);

    public L2CircuitBreaker(boolean enabled, int failureThreshold, long openMillis) {
        this.enabled = enabled;
        this.failureThreshold = failureThreshold > 0 ? failureThreshold : 5;
        this.openMillis = openMillis > 0 ? openMillis : 10_000L;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public State state() {
        return state;
    }

    /**
     * 是否放行本次 Redis 调用。
     *
     * <p>返回 false 表示熔断短路，调用方应直接走降级路径（不要触碰 Redis）。
     * 返回 true 表示放行；若此次是 HALF_OPEN 试探，调用方<b>必须</b>随后调用 {@link #onSuccess()} 或
     * {@link #onFailure()}，否则试探令牌不会释放，熔断器将无法恢复。
     */
    public boolean allowRequest() {
        if (!enabled) {
            return true;
        }
        State s = state;
        if (s == State.CLOSED) {
            return true;
        }
        if (s == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() >= openMillis) {
                // 窗口已到：进入半开，争取唯一试探令牌
                state = State.HALF_OPEN;
                return trialInFlight.compareAndSet(false, true);
            }
            return false;
        }
        // HALF_OPEN：仅放行一个试探
        return trialInFlight.compareAndSet(false, true);
    }

    /**
     * Redis 调用成功（或返回正常结果 / 抛出非连接类异常——说明连接健康）时回调。
     */
    public void onSuccess() {
        if (!enabled) {
            return;
        }
        consecutiveFailures.set(0);
        if (state != State.CLOSED) {
            state = State.CLOSED;
            trialInFlight.set(false);
            log.info("[L2CircuitBreaker] redis recovered -> CLOSED");
        }
    }

    /**
     * Redis 连接类调用失败（超时/连接异常）时回调。
     */
    public void onFailure() {
        if (!enabled) {
            return;
        }
        if (state == State.HALF_OPEN) {
            // 试探失败：重新打开并计时
            openedAt.set(System.currentTimeMillis());
            state = State.OPEN;
            trialInFlight.set(false);
            log.warn("[L2CircuitBreaker] half-open trial failed -> OPEN for {}ms", openMillis);
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold && state == State.CLOSED) {
            openedAt.set(System.currentTimeMillis());
            state = State.OPEN;
            log.warn("[L2CircuitBreaker] failure threshold({}) reached -> OPEN for {}ms", failureThreshold, openMillis);
        }
    }
}
