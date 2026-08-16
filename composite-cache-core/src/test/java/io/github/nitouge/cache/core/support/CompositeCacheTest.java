package io.github.nitouge.cache.core.support;

import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.impl.CompositeCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CompositeCache 的单元测试
 *
 */
@ExtendWith(MockitoExtension.class)
public class CompositeCacheTest {

    @Mock
    private L1Cache l1Cache;

    @Mock
    private L2Cache l2Cache;

    @Mock
    private CacheConfig cacheConfig;

    private CompositeCache compositeCache;

    @BeforeEach
    public void setUp() {
        when(l1Cache.isLoadingCache()).thenReturn(false);
        compositeCache = new CompositeCache("testCache", cacheConfig, l1Cache, l2Cache);
    }

    @Test
    public void get_whenL1CacheHit_shouldReturnFromL1() {
        // 准备
        String key = "key1";
        String expectedValue = "value1";
        when(l1Cache.get(key)).thenReturn(expectedValue);

        // 执行
        Object result = compositeCache.get(key);

        // 断言
        assertThat(result).isEqualTo(expectedValue);
        verify(l1Cache, times(1)).get(key);
        verify(l2Cache, never()).get(any());
    }

    @Test
    public void get_whenL1MissAndL2Hit_shouldReturnFromL2AndUpdateL1() {
        // 准备
        String key = "key1";
        String expectedValue = "value1";
        when(l1Cache.get(key)).thenReturn(null);
        when(l2Cache.get(key)).thenReturn(expectedValue);

        // 执行
        Object result = compositeCache.get(key);

        // 断言
        assertThat(result).isEqualTo(expectedValue);
        verify(l1Cache, times(1)).get(key);
        verify(l2Cache, times(1)).get(key);
        verify(l1Cache, times(1)).put(key, expectedValue);
    }

    @Test
    public void get_whenL1HasNullValuePlaceholder_shortCircuitsAndDoesNotQueryL2() {
        // L1 命中"空值占位"（NullValue）：get 解包后为 null，但 isExists=true 表示 key 确实存在
        String key = "k";
        when(l1Cache.isExists(key)).thenReturn(true);

        Object result = compositeCache.get(key);

        // 返回 null（命中空值），且不穿透到 L2（防穿透在 L1 这一级生效）
        assertThat(result).isNull();
        verify(l2Cache, never()).get(any());
        verify(l2Cache, never()).isExists(any());
    }

    @Test
    public void get_whenBothCacheMiss_shouldReturnNull() {
        // 准备
        String key = "key1";
        when(l1Cache.get(key)).thenReturn(null);
        when(l2Cache.get(key)).thenReturn(null);

        // 执行
        Object result = compositeCache.get(key);

        // 断言
        assertThat(result).isNull();
        verify(l1Cache, times(1)).get(key);
        verify(l2Cache, times(1)).get(key);
        verify(l1Cache, never()).put(any(), any());
    }

    @Test
    public void get_whenLoadingCache_shouldDelegateToL1() {
        // 准备
        when(l1Cache.isLoadingCache()).thenReturn(true);
        // LoadingCache 模式下，CompositeCache 构造函数会调用 l1Cache.getCacheLoader().setL2Cache(...)
        when(l1Cache.getCacheLoader()).thenReturn(mock(io.github.nitouge.cache.core.api.CacheLoader.class));
        compositeCache = new CompositeCache("testCache", cacheConfig, l1Cache, l2Cache);
        String key = "key1";
        String expectedValue = "value1";
        when(l1Cache.get(key)).thenReturn(expectedValue);

        // 执行
        Object result = compositeCache.get(key);

        // 断言
        assertThat(result).isEqualTo(expectedValue);
        verify(l1Cache, times(1)).get(key);
        verify(l2Cache, never()).get(any());
    }

    @Test
    public void getWithLoader_shouldDelegateToL1() throws Exception {
        // 准备
        String key = "key1";
        String expectedValue = "value1";
        Callable<String> valueLoader = () -> expectedValue;
        when(l1Cache.get(eq(key), any(Callable.class))).thenReturn(expectedValue);

        // 执行
        String result = compositeCache.get(key, valueLoader);

        // 断言
        assertThat(result).isEqualTo(expectedValue);
        verify(l1Cache, times(1)).get(eq(key), any(Callable.class));
    }

    @Test
    public void put_shouldUpdateL2ThenL1() {
        // 准备
        String key = "key1";
        String value = "value1";

        // 执行
        compositeCache.put(key, value);

        // 断言
        verify(l2Cache, times(1)).put(key, value);
        verify(l1Cache, times(1)).put(key, value);

        // 验证顺序：先 L2 后 L1
        org.mockito.InOrder inOrder = inOrder(l2Cache, l1Cache);
        inOrder.verify(l2Cache).put(key, value);
        inOrder.verify(l1Cache).put(key, value);
    }

    @Test
    public void evict_shouldDeleteFromL2ThenL1() {
        // 准备
        String key = "key1";

        // 执行
        compositeCache.evict(key);

        // 断言
        verify(l2Cache, times(1)).evict(key);
        verify(l1Cache, times(1)).evict(key);

        // 验证顺序：先 L2 后 L1
        org.mockito.InOrder inOrder = inOrder(l2Cache, l1Cache);
        inOrder.verify(l2Cache).evict(key);
        inOrder.verify(l1Cache).evict(key);
    }

    @Test
    public void clear_shouldClearL2ThenL1() {
        // 执行
        compositeCache.clear();

        // 断言
        verify(l2Cache, times(1)).clear();
        verify(l1Cache, times(1)).clear();

        // 验证顺序：先 L2 后 L1
        org.mockito.InOrder inOrder = inOrder(l2Cache, l1Cache);
        inOrder.verify(l2Cache).clear();
        inOrder.verify(l1Cache).clear();
    }

    @Test
    public void isExists_whenL1Exists_shouldReturnTrue() {
        // 准备
        String key = "key1";
        when(l1Cache.isExists(key)).thenReturn(true);

        // 执行
        boolean result = compositeCache.isExists(key);

        // 断言
        assertThat(result).isTrue();
        verify(l1Cache, times(1)).isExists(key);
        verify(l2Cache, never()).isExists(any());
    }

    @Test
    public void isExists_whenL1NotExistsButL2Exists_shouldReturnTrue() {
        // 准备
        String key = "key1";
        when(l1Cache.isExists(key)).thenReturn(false);
        when(l2Cache.isExists(key)).thenReturn(true);

        // 执行
        boolean result = compositeCache.isExists(key);

        // 断言
        assertThat(result).isTrue();
        verify(l1Cache, times(1)).isExists(key);
        verify(l2Cache, times(1)).isExists(key);
    }

    @Test
    public void isExists_whenBothNotExist_shouldReturnFalse() {
        // 准备
        String key = "key1";
        when(l1Cache.isExists(key)).thenReturn(false);
        when(l2Cache.isExists(key)).thenReturn(false);

        // 执行
        boolean result = compositeCache.isExists(key);

        // 断言
        assertThat(result).isFalse();
        verify(l1Cache, times(1)).isExists(key);
        verify(l2Cache, times(1)).isExists(key);
    }

    @Test
    public void getCacheType_shouldReturnCombinedType() {
        // 准备
        when(l1Cache.getCacheType()).thenReturn("Caffeine");
        when(l2Cache.getCacheType()).thenReturn("Redis");

        // 执行
        String result = compositeCache.getCacheType();

        // 断言
        assertThat(result).isEqualTo("Caffeine + Redis");
    }

    @Test
    public void getCacheName_shouldReturnConfiguredName() {
        // 执行
        String result = compositeCache.getCacheName();

        // 断言
        assertThat(result).isEqualTo("testCache");
    }

    @Test
    public void getActualCache_shouldReturnSelf() {
        // 执行
        CompositeCache result = compositeCache.getActualCache();

        // 断言
        assertThat(result).isSameAs(compositeCache);
    }

    @Test
    public void batchGet_shouldDelegateToPrivateMethod() {
        // 准备
        List<Long> keys = Arrays.asList(1L, 2L, 3L);
        Map<Long, Object> keyMap = new HashMap<>();
        keys.forEach(key -> keyMap.put(key, "key:" + key));
        
        Map<Long, String> l1Result = new HashMap<>();
        l1Result.put(1L, "value1");
        
        when(l1Cache.batchGet(any(), anyBoolean())).thenReturn((Map) l1Result);
        when(l2Cache.batchGet(any(), anyBoolean())).thenReturn(new HashMap<>());

        // 执行
        Map<Long, String> result = compositeCache.batchGet(keyMap, false);

        // 断言
        assertThat(result).isNotNull();
        verify(l1Cache, times(1)).batchGet(any(), eq(true));
    }

    @Test
    public void batchPut_shouldUpdateBothCaches() {
        // 准备
        Map<Object, String> dataMap = new HashMap<>();
        dataMap.put("key1", "value1");
        dataMap.put("key2", "value2");
        
        CacheConfig.RedisConfig redisConfig = mock(CacheConfig.RedisConfig.class);
        when(cacheConfig.getRedis()).thenReturn(redisConfig);
        when(redisConfig.isSupportBatch()).thenReturn(true);

        // 执行
        compositeCache.batchPut(dataMap);

        // 断言
        verify(l1Cache, times(1)).batchPut(dataMap);
        verify(l2Cache, times(1)).batchPut(dataMap);

        // 顺序：先 L2 再 L1（与单条 put 一致）
        org.mockito.InOrder inOrder = inOrder(l2Cache, l1Cache);
        inOrder.verify(l2Cache).batchPut(dataMap);
        inOrder.verify(l1Cache).batchPut(dataMap);
    }

    @Test
    public void batchEvict_shouldDeleteFromBothCaches() {
        // 准备
        Map<Long, Object> keyMap = new HashMap<>();
        keyMap.put(1L, "key:1");
        keyMap.put(2L, "key:2");
        
        CacheConfig.RedisConfig redisConfig = mock(CacheConfig.RedisConfig.class);
        when(cacheConfig.getRedis()).thenReturn(redisConfig);
        when(redisConfig.isSupportBatch()).thenReturn(true);

        // 执行
        compositeCache.batchEvict(keyMap);

        // 断言
        verify(l1Cache, times(1)).batchEvict(keyMap);
        verify(l2Cache, times(1)).batchEvict(keyMap);

        // 顺序：先 L2 再 L1（与单条 evict 一致，避免删 L1 后并发读把 L2 旧数据回填到 L1）
        org.mockito.InOrder inOrder = inOrder(l2Cache, l1Cache);
        inOrder.verify(l2Cache).batchEvict(keyMap);
        inOrder.verify(l1Cache).batchEvict(keyMap);
    }
}
