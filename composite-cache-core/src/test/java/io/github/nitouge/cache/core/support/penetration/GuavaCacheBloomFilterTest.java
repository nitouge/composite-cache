package io.github.nitouge.cache.core.support.penetration;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GuavaCacheBloomFilter} 单元测试。
 *
 */
public class GuavaCacheBloomFilterTest {

    @Test
    public void unregisteredCache_alwaysPassesAndPutIgnored() {
        GuavaCacheBloomFilter f = new GuavaCacheBloomFilter(1000, 0.01);
        assertThat(f.isRegistered("c")).isFalse();
        assertThat(f.mightContain("c", "anything")).isTrue(); // 未注册：放行
        f.put("c", "x"); // 未注册：忽略，不注册
        assertThat(f.isRegistered("c")).isFalse();
    }

    @Test
    public void putThenMightContain_noFalseNegatives() {
        GuavaCacheBloomFilter f = new GuavaCacheBloomFilter(1000, 0.01);
        f.register("c", 1000, 0.01);
        assertThat(f.isRegistered("c")).isTrue();
        for (int i = 0; i < 100; i++) {
            f.put("c", "k" + i);
        }
        for (int i = 0; i < 100; i++) {
            assertThat(f.mightContain("c", "k" + i)).isTrue(); // 放入的一定命中（无假阴性）
        }
    }

    @Test
    public void filtersOutAbsentKeys_withLowFalsePositiveRate() {
        GuavaCacheBloomFilter f = new GuavaCacheBloomFilter(1000, 0.01);
        f.register("c", 1000, 0.01);
        for (int i = 0; i < 100; i++) {
            f.put("c", "k" + i);
        }
        int n = 1000;
        int falsePositives = 0;
        for (int i = 0; i < n; i++) {
            if (f.mightContain("c", "absent-" + i)) {
                falsePositives++;
            }
        }
        // 真实误判率 ~1%，给宽松上界，证明确实在过滤（而非恒为 true）
        assertThat(falsePositives).isLessThan(n / 5);
    }

    @Test
    public void warmUp_registersAndContainsAllKeys() {
        GuavaCacheBloomFilter f = new GuavaCacheBloomFilter(1000, 0.01);
        f.warmUp("c", Arrays.asList(1L, 2L, 3L), 0.01);
        assertThat(f.isRegistered("c")).isTrue();
        assertThat(f.mightContain("c", 1L)).isTrue();
        assertThat(f.mightContain("c", 2L)).isTrue();
        assertThat(f.mightContain("c", 3L)).isTrue();
    }

    @Test
    public void nullKey_passesAndPutDoesNotThrow() {
        GuavaCacheBloomFilter f = new GuavaCacheBloomFilter(1000, 0.01);
        f.register("c", 10, 0.01);
        assertThat(f.mightContain("c", null)).isTrue(); // null：放行
        f.put("c", null); // 不抛异常
    }
}
