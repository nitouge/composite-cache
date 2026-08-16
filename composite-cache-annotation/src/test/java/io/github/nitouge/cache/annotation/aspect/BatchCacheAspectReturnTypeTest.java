package io.github.nitouge.cache.annotation.aspect;

import io.github.nitouge.cache.annotation.BatchCacheAble;
import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link BatchCacheAspect} 返回类型适配回归测试（A1-6）。
 *
 * <p>此前不论方法声明返回 List/Set/数组，切面都固定返回 {@code List}（空输入更是固定 emptyList），
 * 导致 Set/数组 返回的方法在代理边界 ClassCastException。
 *
 */
public class BatchCacheAspectReturnTypeTest {

    private BatchCacheAspect aspect;
    private Cache cache;

    @BeforeEach
    public void setUp() {
        cache = mock(Cache.class);
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCache(anyString())).thenReturn(cache);
        aspect = new BatchCacheAspect();
        aspect.cacheManager = cacheManager;
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return (T) factory.getProxy();
    }

    public static class BatchService {
        @BatchCacheAble(cacheName = "s", keyExtractor = "#result")
        public Set<String> asSet(List<String> ids) {
            return new LinkedHashSet<>();
        }

        @BatchCacheAble(cacheName = "l", keyExtractor = "#result")
        public List<String> asList(List<String> ids) {
            return new ArrayList<>();
        }

        @BatchCacheAble(cacheName = "a", keyExtractor = "#result")
        public String[] asArray(List<String> ids) {
            return new String[0];
        }
    }

    @Test
    public void emptyInput_returnsDeclaredType() {
        BatchService proxy = proxy(new BatchService());
        // 赋给方法声明的返回类型本身就会触发代理边界的 checkcast——返回类型不符则在此抛 CCE
        Set<String> set = proxy.asSet(Collections.emptyList());
        assertThat(set).isEmpty();

        List<String> list = proxy.asList(Collections.emptyList());
        assertThat(list).isEmpty();

        String[] arr = proxy.asArray(Collections.emptyList());
        assertThat(arr).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    public void nonEmptyInput_setReturn_returnsSet() {
        Map<Object, Object> resultMap = new LinkedHashMap<>();
        resultMap.put("a", "a");
        resultMap.put("b", "b");
        when(cache.batchGetOrLoad(any(), any(), anyBoolean())).thenReturn((Map) resultMap);

        BatchService proxy = proxy(new BatchService());
        Set<String> res = proxy.asSet(Arrays.asList("a", "b"));
        assertThat(res).containsExactly("a", "b");
    }
}
