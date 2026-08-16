package io.github.nitouge.cache.core.health;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CacheHealthIndicator} 回归测试（C15-1 / C15-2 / C15-6）。
 *
 */
public class CacheHealthIndicatorTest {

    @Test
    public void managerNull_isDown() {
        Health h = new CacheHealthIndicator(null).health();
        assertThat(h.getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    public void allReachable_isUp() {
        CacheManager cm = mock(CacheManager.class);
        when(cm.getCacheNames()).thenReturn(Collections.singletonList("c1"));
        when(cm.getCache("c1")).thenReturn(mock(Cache.class));

        Health h = new CacheHealthIndicator(cm).health();
        assertThat(h.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    public void cacheProbeThrows_overallStaysUp_withDegradedDetail() {
        CacheManager cm = mock(CacheManager.class);
        when(cm.getCacheNames()).thenReturn(Collections.singletonList("c1"));
        Cache cache = mock(Cache.class);
        when(cm.getCache("c1")).thenReturn(cache);
        when(cache.isExists(anyString())).thenThrow(new RuntimeException("redis down"));

        Health h = new CacheHealthIndicator(cm).health();

        // 单个缓存探活异常不把整应用拉成 DOWN（C15-2），降级体现在 details（C15-1 同向）
        assertThat(h.getStatus()).isEqualTo(Status.UP);
        assertThat(h.getDetails()).containsKey("degradedCaches");
        assertThat(h.getDetails().get("degradedCaches")).isEqualTo(1);
    }
}
