package io.github.nitouge.cache.core.wrapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link LogicalExpireWrapper} 过期逻辑的单元测试。
 *
 */
public class LogicalExpireWrapperTest {

    @Test
    public void notExpired_whenNowBeforeExpireAt() {
        LogicalExpireWrapper w = new LogicalExpireWrapper("v", 1000L);
        assertThat(w.isExpired(999L)).isFalse();
        assertThat(w.isExpired(1000L)).isFalse(); // 边界：等于不算过期
    }

    @Test
    public void expired_whenNowAfterExpireAt() {
        LogicalExpireWrapper w = new LogicalExpireWrapper("v", 1000L);
        assertThat(w.isExpired(1001L)).isTrue();
    }

    @Test
    public void neverExpires_whenMaxValue() {
        LogicalExpireWrapper w = new LogicalExpireWrapper("v", Long.MAX_VALUE);
        assertThat(w.isExpired(System.currentTimeMillis())).isFalse();
    }

    @Test
    public void dataIsPreserved() {
        LogicalExpireWrapper w = new LogicalExpireWrapper("hello", 1L);
        assertThat(w.getData()).isEqualTo("hello");
    }
}
