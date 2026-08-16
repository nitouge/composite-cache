package io.github.nitouge.cache.core.impl;

import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L2CacheSetting;
import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;
import io.github.nitouge.cache.core.impl.level2.RedissonRBucketCache;
import io.github.nitouge.cache.core.wrapper.NullValueWrapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RedissonRBucketCache#putIfAbsent} 回归测试（C3-2）。
 *
 * <p>此前 putIfAbsent 直接 {@code trySet(value)}，不做 NullValue 转换/逻辑过期/降级，相对父类默认退化。
 * 修复后与 put 对齐：null 值写入 {@link NullValueWrapper} 占位，旧值解包返回。
 *
 */
public class RedissonRBucketCachePutIfAbsentTest {

    @SuppressWarnings("unchecked")
    @Test
    public void putIfAbsent_nullValue_storesNullValueWrapper_andReturnsOldUnwrapped() {
        CacheConfig config = new CacheConfig();
        config.getRedis().setLoadStrategy(RedisLoadStrategyEnum.NONE);
        // allowNullValues 默认 true
        CacheSetting setting = new CacheSetting();
        setting.setL2CacheSetting(new L2CacheSetting());

        RBucket<Object> bucket = mock(RBucket.class);
        when(bucket.get()).thenReturn(NullValueWrapper.NULL_VALUE_WRAPPER); // 旧值为空值占位
        RedissonClient client = mock(RedissonClient.class);
        doReturn(bucket).when(client).getBucket(anyString());

        RedissonRBucketCache cache = new RedissonRBucketCache("c", config, setting, client);

        Object old = cache.putIfAbsent("k", null);

        // 旧值解包：NullValue → null
        assertThat(old).isNull();
        // 修复后：写入 NullValueWrapper 占位（带 NullValue TTL），而非裸 null
        verify(bucket).trySet(any(NullValueWrapper.class), anyLong(), any(TimeUnit.class));
    }
}
