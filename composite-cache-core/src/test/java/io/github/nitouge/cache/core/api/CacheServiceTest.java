package io.github.nitouge.cache.core.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link CacheService} 的默认方法使用业务 cacheName 和原始 key（基本类型的 buildCacheKey 为恒等映射）
 * 委托给 {@link CacheTemplate}。
 *
 */
public class CacheServiceTest {

    private CacheTemplate template;
    private UserCacheService service;

    /** 由模拟的 CacheTemplate 支持的具体 CacheService 实现。 */
    static class UserCacheService implements CacheService<Long, String> {
        private final CacheTemplate template;

        UserCacheService(CacheTemplate template) {
            this.template = template;
        }

        @Override
        public CacheTemplate getCacheTemplate() {
            return template;
        }

        @Override
        public String getCacheName() {
            return "user";
        }

        @Override
        public String queryData(Long key) {
            return "db-" + key;
        }

        @Override
        public Map<Long, String> queryDataList(List<Long> keyList) {
            Map<Long, String> m = new HashMap<>();
            keyList.forEach(k -> m.put(k, "db-" + k));
            return m;
        }
    }

    @BeforeEach
    public void setUp() {
        template = mock(CacheTemplate.class);
        service = new UserCacheService(template);
    }

    @Test
    public void buildCacheKey_primitive_isIdentity() {
        // 与注解 keyExpr="#id" 对齐：基本类型 key 原样返回（Long 1，而非 "1"）
        assertThat(service.buildCacheKey(1L)).isEqualTo(1L);
    }

    @Test
    public void getOrLoad_delegatesWithRawKey() {
        when(template.getOrLoad(eq("user"), eq(1L), any(Callable.class))).thenReturn("v");
        String r = service.getOrLoad(1L);
        assertThat(r).isEqualTo("v");
        verify(template, times(1)).getOrLoad(eq("user"), eq(1L), any(Callable.class));
    }

    @Test
    public void get_put_evict_exists_delegate() {
        when(template.get("user", 1L)).thenReturn("v");
        when(template.exists("user", 1L)).thenReturn(true);

        assertThat(service.get(1L)).isEqualTo("v");
        service.put(1L, "v2");
        service.evict(1L);
        assertThat(service.isExists(1L)).isTrue();

        verify(template).get("user", 1L);
        verify(template).put("user", 1L, "v2");
        verify(template).evict("user", 1L);
        verify(template).exists("user", 1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void batchGet_delegatesWithIdentityKeyBuilder() {
        List<Long> keys = Arrays.asList(1L, 2L);
        when(template.batchGet(eq("user"), eq(keys), any(Function.class))).thenReturn(new HashMap<>());

        service.batchGet(keys);

        ArgumentCaptor<Function<Long, Object>> captor = ArgumentCaptor.forClass(Function.class);
        verify(template).batchGet(eq("user"), eq(keys), captor.capture());
        assertThat(captor.getValue().apply(9L)).isEqualTo(9L);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void batchGetOrLoad_delegatesWithQueryDataListLoader() {
        List<Long> keys = Arrays.asList(1L, 2L);
        when(template.batchGetOrLoad(eq("user"), eq(keys), any(Function.class), any(Function.class)))
                .thenReturn(new HashMap<>());

        service.batchGetOrLoad(keys);

        verify(template).batchGetOrLoad(eq("user"), eq(keys), any(Function.class), any(Function.class));
    }
}
