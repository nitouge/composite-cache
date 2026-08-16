package io.github.nitouge.cache.core.impl;

import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L2CacheSetting;
import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;
import io.github.nitouge.cache.core.impl.level2.RedissonRBucketCache;
import io.github.nitouge.cache.core.wrapper.NullValueWrapper;
import org.junit.Test;
import org.redisson.api.BatchResult;
import org.redisson.api.RBatch;
import org.redisson.api.RBucketAsync;
import org.redisson.api.RFuture;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RedissonRBucketCache#batchGet} 回归测试（C3-1）。
 *
 * <p>历史 bug：命中列表用 {@code ConcurrentHashMap} 存储，当批量查询命中一个 NullValue（防穿透空值）
 * 且 {@code returnNullValueKey=true} 时，会执行 {@code hitMap.put(key, null)}——ConcurrentHashMap
 * 不允许 null 值 → 抛 NPE，使空值命中被静默丢弃、退化为缓存穿透。修复改为
 * {@code Collections.synchronizedMap(HashMap)}（线程安全且允许 null）。
 *
 * <p>用 mock 的 RBatch/RFuture 模拟批量异步读返回一个 NullValueWrapper，验证不再 NPE 且空值 key 被保留。
 *
 */
public class RedissonRBucketCacheBatchGetTest {

    @SuppressWarnings("unchecked")
    private RedissonRBucketCache buildCache(Object asyncValue) {
        CacheConfig config = new CacheConfig();
        config.getRedis().setLoadStrategy(RedisLoadStrategyEnum.NONE);

        CacheSetting setting = new CacheSetting();
        setting.setL2CacheSetting(new L2CacheSetting());

        RedissonClient client = mock(RedissonClient.class);
        RBatch batch = mock(RBatch.class);
        when(client.createBatch()).thenReturn(batch);

        RBucketAsync<Object> bucketAsync = mock(RBucketAsync.class);
        when(batch.getBucket(anyString())).thenReturn(bucketAsync);

        RFuture<Object> future = mock(RFuture.class);
        when(bucketAsync.getAsync()).thenReturn(future);

        // 使用 doAnswer 捕获 whenComplete 的回调参数，立即同步执行
        doAnswer(invocation -> {
            Object callback = invocation.getArgument(0);
            if (callback instanceof BiConsumer) {
                ((BiConsumer<Object, Throwable>) callback).accept(asyncValue, null);
            }
            return future;
        }).when(future).whenComplete(any(BiConsumer.class));

        when(batch.execute()).thenReturn(mock(BatchResult.class));

        return new RedissonRBucketCache("user", config, setting, client);
    }

    @Test
    public void batchGet_whenNullValueHit_andReturnNullValueKey_doesNotThrowAndKeepsKey() {
        RedissonRBucketCache cache = buildCache(NullValueWrapper.NULL_VALUE_WRAPPER);

        Map<Object, Object> keyMap = new HashMap<>();
        keyMap.put("k1", "k1");

        Map<Object, Object> result = cache.batchGet(keyMap, true);

        assertThat(result).containsKey("k1");
        assertThat(result.get("k1")).isNull();
    }

    @Test
    public void batchGet_whenRealValueHit_returnsValue() {
        RedissonRBucketCache cache = buildCache("real-value");

        Map<Object, Object> keyMap = new HashMap<>();
        keyMap.put("k1", "k1");

        Map<Object, Object> result = cache.batchGet(keyMap, true);
        assertThat(result.get("k1")).isEqualTo("real-value");
    }
}
