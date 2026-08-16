package io.github.nitouge.cache.annotation.key;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SmartCacheKeyGenerator} 单参 {@code generate(Object)} 回归测试（A2-2）。
 *
 * <p>此前 SMART 未覆盖单参方法，导致 strategy=SMART 在 keyExpr 路径下退化为接口默认（原样返回），
 * "智能压缩"从不生效。
 *
 */
public class SmartCacheKeyGeneratorTest {

    /** 阈值设小（50）便于触发 MD5 压缩。 */
    private final SmartCacheKeyGenerator generator = new SmartCacheKeyGenerator(50, 5);

    @Test
    public void simpleValue_returnedAsIs() {
        // 简单值原样返回，保证与编程式业务 key 对齐
        assertThat(generator.generate(123L)).isEqualTo(123L);
        assertThat(generator.generate("user:1")).isEqualTo("user:1");
        assertThat(generator.generate(42)).isEqualTo(42);
    }

    @Test
    public void nullValue_returnsEmptyKey() {
        assertThat(generator.generate((Object) null)).isEqualTo(DefaultKey.EMPTY);
    }

    @Test
    public void smallCollection_returnsCompactString() {
        assertThat(generator.generate(Arrays.asList(1, 2, 3))).isEqualTo("[1,2,3]");
    }

    @Test
    public void largeComplexValue_isMd5Compressed() {
        // 复杂值且紧凑表示超过阈值(50) → MD5 压缩
        List<String> big = Arrays.asList(
                "aaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbb", "cccccccccccccccccccc");
        Object key = generator.generate(big);
        assertThat(key).isInstanceOf(String.class);
        assertThat((String) key).startsWith("MD5:");
    }
}
