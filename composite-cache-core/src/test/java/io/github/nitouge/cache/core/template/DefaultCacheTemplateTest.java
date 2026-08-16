package io.github.nitouge.cache.core.template;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.api.CacheTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证 {@link DefaultCacheTemplate} 使用原始业务 key（无 "cacheName:" 前缀）委托给 {@link Cache}，
 * 以便与注解 keyExpr 保持一致。
 *
 */
@ExtendWith(MockitoExtension.class)
public class DefaultCacheTemplateTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    private CacheTemplate template;

    @BeforeEach
    public void setUp() {
        lenient().when(cacheManager.getCache("user")).thenReturn(cache);
        template = new DefaultCacheTemplate(cacheManager);
    }

    @Test
    public void getOrLoad_usesRawKey() {
        Callable<String> loader = () -> "loaded";
        when(cache.get(eq(1L), any(Callable.class))).thenReturn("v");

        String result = template.getOrLoad("user", 1L, loader);

        assertThat(result).isEqualTo("v");
        // 关键：用原始 key 1L，而不是 "user:1"
        verify(cache, times(1)).get(eq(1L), any(Callable.class));
    }

    @Test
    public void get_usesRawKeyAndGetIfPresent() {
        when(cache.getIfPresent(1L)).thenReturn("v");
        Object v = template.get("user", 1L);
        assertThat(v).isEqualTo("v");
        verify(cache, times(1)).getIfPresent(1L);
    }

    @Test
    public void put_usesRawKey() {
        template.put("user", 1L, "v");
        verify(cache, times(1)).put(1L, "v");
    }

    @Test
    public void evict_usesRawKey() {
        template.evict("user", 1L);
        verify(cache, times(1)).evict(1L);
    }

    @Test
    public void exists_usesRawKey() {
        when(cache.isExists(1L)).thenReturn(true);
        assertThat(template.exists("user", 1L)).isTrue();
        verify(cache, times(1)).isExists(1L);
    }

    @Test
    public void get_whenCacheMissing_returnsNull() {
        when(cacheManager.getCache("absent")).thenReturn(null);
        assertThat((Object) template.get("absent", 1L)).isNull();
    }
}
