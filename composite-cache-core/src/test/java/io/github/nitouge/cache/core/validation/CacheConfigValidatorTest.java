package io.github.nitouge.cache.core.validation;

import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.exception.CacheConfigException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CacheConfigValidator} 回归测试。
 *
 * <p>C12-1：移除冗余的 validateCacheType 与死分支后，默认 L1_L2 配置仍应校验通过。
 * <p>C12-5：新增数值/降级/抖动/布隆字段的范围校验。
 *
 */
public class CacheConfigValidatorTest {

    @Test
    public void defaultConfig_shouldPass() {
        // 默认 L1_L2（CAFFEINE + REDIS）+ 各默认值均合法
        assertThatCode(() -> CacheConfigValidator.validate(new CacheConfig())).doesNotThrowAnyException();
    }

    @Test
    public void negativeTtlJitterRatio_shouldThrow() {
        CacheConfig config = new CacheConfig();
        config.getRedis().setTtlJitterRatio(-0.1);
        assertThatThrownBy(() -> CacheConfigValidator.validate(config))
                .isInstanceOf(CacheConfigException.class);
    }

    @Test
    public void logicalExpireFactorLessThanOne_shouldThrow() {
        CacheConfig config = new CacheConfig();
        config.getRedis().setLogicalExpirePhysicalTtlFactor(0);
        assertThatThrownBy(() -> CacheConfigValidator.validate(config))
                .isInstanceOf(CacheConfigException.class);
    }

    @Test
    public void nonPositiveDegradeThreshold_shouldThrow() {
        CacheConfig config = new CacheConfig();
        config.getRedis().setDegradeFailureThreshold(0);
        assertThatThrownBy(() -> CacheConfigValidator.validate(config))
                .isInstanceOf(CacheConfigException.class);
    }

    @Test
    public void negativeDegradeOpenMillis_shouldThrow() {
        CacheConfig config = new CacheConfig();
        config.getRedis().setDegradeOpenMillis(-1L);
        assertThatThrownBy(() -> CacheConfigValidator.validate(config))
                .isInstanceOf(CacheConfigException.class);
    }

    @Test
    public void bloomFppOutOfRange_shouldThrow() {
        CacheConfig high = new CacheConfig();
        high.getPenetration().setBloomFpp(1.5);
        assertThatThrownBy(() -> CacheConfigValidator.validate(high))
                .isInstanceOf(CacheConfigException.class);

        CacheConfig zero = new CacheConfig();
        zero.getPenetration().setBloomFpp(0d);
        assertThatThrownBy(() -> CacheConfigValidator.validate(zero))
                .isInstanceOf(CacheConfigException.class);
    }

    @Test
    public void nonPositiveBloomInsertions_shouldThrow() {
        CacheConfig config = new CacheConfig();
        config.getPenetration().setBloomExpectedInsertions(0L);
        assertThatThrownBy(() -> CacheConfigValidator.validate(config))
                .isInstanceOf(CacheConfigException.class);
    }
}
