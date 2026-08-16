package io.github.nitouge.cache.core.impl;

import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L2CacheSetting;
import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;
import io.github.nitouge.cache.core.impl.level2.RedissonRBucketCache;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedissonRBucketCache} 运行期降级（P2-J）集成测试：用 mock 的 RBucket 抛 {@code RedisException}
 * 模拟 Redis 不可用，验证 get(key,callable) 在降级开启时回落到 loader，并在熔断打开后短路（不再触碰 Redis）。
 *
 */
public class RedissonRBucketCacheDegradeTest {

    private RedissonRBucketCache buildCache(boolean degradeEnabled, RBucket<Object> bucket) {
        CacheConfig config = new CacheConfig();
        config.getRedis().setLoadStrategy(RedisLoadStrategyEnum.NONE);
        config.getRedis().setDegradeEnabled(degradeEnabled);
        config.getRedis().setDegradeFailureThreshold(2);
        config.getRedis().setDegradeOpenMillis(10_000L);

        CacheSetting setting = new CacheSetting();
        setting.setL2CacheSetting(new L2CacheSetting());

        RedissonClient client = mock(RedissonClient.class);
        doReturn(bucket).when(client).getBucket(anyString());

        return new RedissonRBucketCache("stock", config, setting, client);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void whenRedisDown_getWithLoader_degradesToLoaderThenShortCircuits() throws Exception {
        RBucket<Object> bucket = mock(RBucket.class);
        when(bucket.get()).thenThrow(new RedisException("redis down"));
        RedissonRBucketCache cache = buildCache(true, bucket);

        Callable<String> loader = () -> "db-value";

        // 前两次：bucket.get() 抛连接异常 → 降级回源，并累计熔断失败（第 2 次达阈值打开熔断）
        assertThat(cache.get("1", loader)).isEqualTo("db-value");
        assertThat(cache.get("1", loader)).isEqualTo("db-value");
        // 第三次：熔断已打开 → 直接短路降级，不再调用 Redis
        assertThat(cache.get("1", loader)).isEqualTo("db-value");

        // bucket.get() 仅在前两次被调用，第三次被熔断短路
        verify(bucket, times(2)).get();
    }

    @SuppressWarnings("unchecked")
    @Test
    public void whenDegradeDisabled_redisErrorPropagates() {
        RBucket<Object> bucket = mock(RBucket.class);
        when(bucket.get()).thenThrow(new RedisException("redis down"));
        RedissonRBucketCache cache = buildCache(false, bucket);

        // 降级关闭（默认）：Redis 连接异常照常向上抛出，不被吞、不降级
        assertThatThrownBy(() -> cache.get("1", () -> "db-value"))
                .isInstanceOf(RuntimeException.class);
    }
}
