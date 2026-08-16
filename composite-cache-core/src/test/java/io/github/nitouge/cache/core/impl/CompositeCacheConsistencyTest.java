package io.github.nitouge.cache.core.impl;

import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.consistency.CacheDeleteCompensation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompositeCache#evict(Object)} 中一致性集成的测试：
 * 延迟双删与删除失败补偿。
 *
 */
@ExtendWith(MockitoExtension.class)
public class CompositeCacheConsistencyTest {

    @Mock
    private L1Cache l1Cache;

    @Mock
    private L2Cache l2Cache;

    @Mock
    private CacheConfig cacheConfig;

    private CompositeCache newCache(CacheConfig.ConsistencyConfig consistency) {
        when(l1Cache.isLoadingCache()).thenReturn(false);
        // 部分用例（L2 删除失败且补偿关闭）会在调用 getConsistency() 前短路，故用 lenient 避免严格存根告警
        org.mockito.Mockito.lenient().when(cacheConfig.getConsistency()).thenReturn(consistency);
        return new CompositeCache("testCache", cacheConfig, l1Cache, l2Cache);
    }

    @BeforeEach
    public void setUp() {
        // 各用例自行构建 CompositeCache 以注入不同的一致性配置
    }

    @Test
    public void evict_whenDelayedDoubleDeleteEnabled_shouldScheduleSecondDelete() {
        CacheConfig.ConsistencyConfig consistency = new CacheConfig.ConsistencyConfig()
                .setDelayedDoubleDelete(true)
                .setDelayedDoubleDeleteMillis(500L);
        CompositeCache cache = newCache(consistency);

        ScheduledExecutorService scheduler = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        cache.setConsistencyScheduler(scheduler);

        cache.evict("k1");

        // 立即删除：L2 在前，L1 在后，各一次
        verify(l2Cache, times(1)).evict("k1");
        verify(l1Cache, times(1)).evict("k1");

        // 捕获延迟任务并执行，验证发生第二次删除
        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(1)).schedule(taskCaptor.capture(), eq(500L), eq(TimeUnit.MILLISECONDS));

        taskCaptor.getValue().run();

        verify(l2Cache, times(2)).evict("k1");
        verify(l1Cache, times(2)).evict("k1");
    }

    @Test
    public void evict_whenDelayedDoubleDeleteDisabled_shouldNotSchedule() {
        CacheConfig.ConsistencyConfig consistency = new CacheConfig.ConsistencyConfig(); // 默认全关
        CompositeCache cache = newCache(consistency);

        ScheduledExecutorService scheduler = org.mockito.Mockito.mock(ScheduledExecutorService.class);
        cache.setConsistencyScheduler(scheduler);

        cache.evict("k1");

        verify(l2Cache, times(1)).evict("k1");
        verify(l1Cache, times(1)).evict("k1");
        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    public void evict_whenL2FailsAndCompensationEnabled_shouldRecordAndNotThrow() {
        CacheConfig.ConsistencyConfig consistency = new CacheConfig.ConsistencyConfig()
                .setDeleteCompensation(true);
        CompositeCache cache = newCache(consistency);

        CacheDeleteCompensation compensation = org.mockito.Mockito.mock(CacheDeleteCompensation.class);
        cache.setDeleteCompensation(compensation);

        doThrow(new RuntimeException("redis down")).when(l2Cache).evict("k1");

        // 不应抛出异常
        cache.evict("k1");

        verify(compensation, times(1)).recordDeleteFailure("testCache", "k1");
        // L1 仍应被删除
        verify(l1Cache, times(1)).evict("k1");
    }

    @Test
    public void evict_whenL2FailsAndCompensationDisabled_shouldPropagate() {
        CacheConfig.ConsistencyConfig consistency = new CacheConfig.ConsistencyConfig(); // 补偿关闭
        CompositeCache cache = newCache(consistency);

        doThrow(new RuntimeException("redis down")).when(l2Cache).evict("k1");

        // 关闭补偿时，保持原有行为：异常向上抛出
        assertThatThrownBy(() -> cache.evict("k1"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("redis down");

        verify(l1Cache, never()).evict("k1");
    }
}
