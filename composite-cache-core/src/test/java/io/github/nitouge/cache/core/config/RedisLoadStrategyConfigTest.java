package io.github.nitouge.cache.core.config;

import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试 {@link CacheConfig.RedisConfig} 中有效 L2 加载策略的向后兼容推导逻辑。
 *
 */
public class RedisLoadStrategyConfigTest {

    @Test
    public void default_noStrategyAndLockFalse_isNone() {
        CacheConfig.RedisConfig redis = new CacheConfig.RedisConfig();
        // 默认 lock=false, loadStrategy=null
        assertThat(redis.getEffectiveLoadStrategy()).isEqualTo(RedisLoadStrategyEnum.NONE);
    }

    @Test
    public void legacyLockTrue_derivesLock() {
        CacheConfig.RedisConfig redis = new CacheConfig.RedisConfig();
        redis.setLock(true); // 旧配置
        assertThat(redis.getEffectiveLoadStrategy()).isEqualTo(RedisLoadStrategyEnum.LOCK);
    }

    @Test
    public void explicitStrategy_overridesLockBoolean() {
        CacheConfig.RedisConfig redis = new CacheConfig.RedisConfig();
        redis.setLock(true);
        redis.setLoadStrategy(RedisLoadStrategyEnum.LOGICAL_EXPIRE);
        // 显式配置优先于 lock 布尔
        assertThat(redis.getEffectiveLoadStrategy()).isEqualTo(RedisLoadStrategyEnum.LOGICAL_EXPIRE);
    }

    @Test
    public void explicitNone_overridesLockTrue() {
        CacheConfig.RedisConfig redis = new CacheConfig.RedisConfig();
        redis.setLock(true);
        redis.setLoadStrategy(RedisLoadStrategyEnum.NONE);
        assertThat(redis.getEffectiveLoadStrategy()).isEqualTo(RedisLoadStrategyEnum.NONE);
    }
}
