package io.github.nitouge.cache.core.support.degrade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link L2CircuitBreaker} 状态机单元测试（纯逻辑，无 Redis）。
 *
 */
public class L2CircuitBreakerTest {

    @Test
    public void disabled_alwaysAllowsAndStaysClosed() {
        L2CircuitBreaker b = new L2CircuitBreaker(false, 1, 1000);
        b.onFailure();
        b.onFailure();
        b.onFailure();
        assertThat(b.allowRequest()).isTrue();
        assertThat(b.state()).isEqualTo(L2CircuitBreaker.State.CLOSED);
    }

    @Test
    public void opensAfterConsecutiveFailuresReachThreshold() {
        L2CircuitBreaker b = new L2CircuitBreaker(true, 3, 10_000);
        assertThat(b.allowRequest()).isTrue();
        b.onFailure();
        b.onFailure();
        assertThat(b.state()).isEqualTo(L2CircuitBreaker.State.CLOSED);
        b.onFailure(); // 第 3 次 -> 打开
        assertThat(b.state()).isEqualTo(L2CircuitBreaker.State.OPEN);
        assertThat(b.allowRequest()).isFalse(); // 窗口内短路
    }

    @Test
    public void successResetsConsecutiveFailureCount() {
        L2CircuitBreaker b = new L2CircuitBreaker(true, 3, 10_000);
        b.onFailure();
        b.onFailure();
        b.onSuccess(); // 重置计数
        b.onFailure();
        b.onFailure();
        // 重置后仅累计 2 次，未达阈值 3
        assertThat(b.state()).isEqualTo(L2CircuitBreaker.State.CLOSED);
    }

    @Test
    public void halfOpenTrialThenSuccessRecovers() throws InterruptedException {
        L2CircuitBreaker b = new L2CircuitBreaker(true, 1, 50);
        b.onFailure(); // 打开
        assertThat(b.state()).isEqualTo(L2CircuitBreaker.State.OPEN);
        assertThat(b.allowRequest()).isFalse();
        Thread.sleep(70);
        assertThat(b.allowRequest()).isTrue();  // 半开：放行一个试探
        assertThat(b.allowRequest()).isFalse(); // 同一时刻只允许一个试探
        b.onSuccess();
        assertThat(b.state()).isEqualTo(L2CircuitBreaker.State.CLOSED);
        assertThat(b.allowRequest()).isTrue();
    }

    @Test
    public void halfOpenTrialFailureReopens() throws InterruptedException {
        L2CircuitBreaker b = new L2CircuitBreaker(true, 1, 50);
        b.onFailure(); // 打开
        Thread.sleep(70);
        assertThat(b.allowRequest()).isTrue(); // 试探
        b.onFailure(); // 试探失败 -> 重新打开
        assertThat(b.state()).isEqualTo(L2CircuitBreaker.State.OPEN);
        assertThat(b.allowRequest()).isFalse();
    }
}
