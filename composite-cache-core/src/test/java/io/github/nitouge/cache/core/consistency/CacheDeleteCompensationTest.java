package io.github.nitouge.cache.core.consistency;

import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link CacheDeleteCompensation} 防御性守卫回归测试（C9-2 / C9-3）。
 *
 */
public class CacheDeleteCompensationTest {

    /** 大间隔，避免后台补偿任务在短测试期内运行。 */
    private CacheDeleteCompensation newCompensation(RedissonClient client) {
        return new CacheDeleteCompensation(client, 3, 3600L);
    }

    @Test
    public void recordDeleteFailure_nullKey_shouldNotThrowNorTouchQueue() {
        RedissonClient client = mock(RedissonClient.class);
        try (CacheDeleteCompensation compensation = newCompensation(client)) {
            // C9-2：key 为 null 时此前 key.toString() 会 NPE；现应安全返回、不触碰队列
            assertThatCode(() -> compensation.recordDeleteFailure("user", null)).doesNotThrowAnyException();
            verify(client, never()).getQueue(anyString());
        }
    }

    @Test
    public void deleteCacheWithRecord_whenCacheManagerNotWired_shouldNotThrow() {
        RedissonClient client = mock(RedissonClient.class);
        try (CacheDeleteCompensation compensation = newCompensation(client)) {
            // C9-3：未注入 CacheManager 时此前会 NPE；现应安全返回
            assertThatCode(() -> compensation.deleteCacheWithRecord("user", "k1")).doesNotThrowAnyException();
        }
    }
}
