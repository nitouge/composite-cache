package io.github.nitouge.cache.core.wrapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NullValueWrapper#equals} 契约回归测试（C3-3）。
 *
 * <p>历史 bug：{@code equals(Object obj){ return this == obj || obj == null; }}——
 * 使 {@code x.equals(null)} 返回 true，违反 {@link Object#equals} 契约。
 *
 */
public class NullValueWrapperTest {

    @Test
    public void equalsNull_shouldBeFalse() {
        // 契约：x.equals(null) 必须为 false
        assertThat(NullValueWrapper.NULL_VALUE_WRAPPER.equals(null)).isFalse();
    }

    @Test
    public void equalsSelf_shouldBeTrue() {
        assertThat(NullValueWrapper.NULL_VALUE_WRAPPER.equals(NullValueWrapper.NULL_VALUE_WRAPPER)).isTrue();
    }

    @Test
    public void equalsOtherType_shouldBeFalse() {
        assertThat(NullValueWrapper.NULL_VALUE_WRAPPER.equals("not-a-null-value")).isFalse();
        assertThat(NullValueWrapper.NULL_VALUE_WRAPPER.equals(new Object())).isFalse();
    }

    @Test
    public void hashCode_shouldBeStable() {
        assertThat(NullValueWrapper.NULL_VALUE_WRAPPER.hashCode())
                .isEqualTo(NullValueWrapper.NULL_VALUE_WRAPPER.hashCode());
    }
}
