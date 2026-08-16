package io.github.nitouge.cache.annotation.aspect;

import io.github.nitouge.cache.annotation.CacheAble;
import io.github.nitouge.cache.annotation.CacheEvict;
import io.github.nitouge.cache.annotation.CachePut;
import io.github.nitouge.cache.annotation.Cache_L1;
import io.github.nitouge.cache.annotation.Caches;
import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.consts.enums.CacheExpireModeEnum;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompositeAspect} 行为回归测试（A1-1 / A1-2），用 AspectJProxyFactory 真实织入切面。
 *
 */
public class CompositeAspectBehaviorTest {

    private Cache cache;
    private CompositeAspect aspect;

    @BeforeEach
    @SuppressWarnings("unchecked")
    public void setUp() {
        cache = mock(Cache.class);
        // get(key, callable) 执行加载回调（模拟缓存未命中→回源），并透传其异常
        when(cache.get(any(), any(Callable.class))).thenAnswer(inv -> {
            Callable<?> c = inv.getArgument(1);
            return c.call();
        });
        CacheManager cacheManager = mock(CacheManager.class);
        when(cacheManager.getCache(anyString())).thenReturn(cache);

        aspect = new CompositeAspect();
        aspect.cacheManager = cacheManager; // 同包可直接注入
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        return (T) factory.getProxy();
    }

    // ===== A1-1：用户方法抛错时不应因 ignoreException 重复执行 =====

    public static class FailingService {
        int calls = 0;

        @CacheAble(cacheName = "u", keyExpr = "'k'", ignoreException = true)
        public String failing() {
            calls++;
            throw new IllegalStateException("boom");
        }
    }

    @Test
    public void cacheAble_whenMethodThrows_executesOnce_andPropagatesOriginal() {
        FailingService target = new FailingService();
        FailingService proxy = proxy(target);

        // 用户方法自身抛错：原样抛出（不被 ignoreException 当作缓存故障而重试）
        assertThatThrownBy(proxy::failing)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        // 关键：方法只执行一次（修复前会因 ignoreException 兜底再次执行 → calls==2）
        assertThat(target.calls).isEqualTo(1);
    }

    public static class OkService {
        int calls = 0;

        @CacheAble(cacheName = "u", keyExpr = "'k'")
        public String ok() {
            calls++;
            return "value";
        }
    }

    @Test
    public void cacheAble_whenMethodOk_executesOnce_andReturns() {
        OkService target = new OkService();
        OkService proxy = proxy(target);

        assertThat(proxy.ok()).isEqualTo("value");
        assertThat(target.calls).isEqualTo(1);
    }

    // ===== A1-2：@Caches 中方法执行一次，所有子注解都生效 =====

    public static class ComboService {
        int calls = 0;

        @Caches(
                cacheEvict = @CacheEvict(cacheName = "e", keyExpr = "'ek'"),
                cachePut = @CachePut(cacheName = "p", keyExpr = "'pk'"),
                cacheAble = {
                        @CacheAble(cacheName = "a1", keyExpr = "'k1'"),
                        @CacheAble(cacheName = "a2", keyExpr = "'k2'")
                }
        )
        public String combo() {
            calls++;
            return "v";
        }
    }

    @Test
    public void caches_executesMethodOnce_andAppliesAllSubAnnotations() {
        ComboService target = new ComboService();
        ComboService proxy = proxy(target);

        assertThat(proxy.combo()).isEqualTo("v");

        // 方法只执行一次
        assertThat(target.calls).isEqualTo(1);
        // evict 生效一次
        verify(cache, times(1)).evict(any());
        // 1 个 @CachePut + 2 个 @CacheAble 都把结果写入缓存 = 3 次 put
        // （修复前：含 Evict/Put 时 @CacheAble 永不写、多个 @CacheAble 只处理第一个 → 仅 1 次 put）
        verify(cache, times(3)).put(any(), eq("v"));
        // @Caches 内 @CacheAble 作 put 处理，不再走 read-through 的 get(key,callable)
        verify(cache, times(0)).get(any(), any(Callable.class));
    }

    // ===== expireMode 接线：@Cache_L1.expireMode 透传到 CacheSetting =====

    public static class AccessModeService {
        @CacheAble(cacheName = "am", keyExpr = "'k'", cacheMode = CacheModeEnum.L1,
                cacheL1 = @Cache_L1(expireMode = CacheExpireModeEnum.ACCESS))
        public String read() {
            return "v";
        }
    }

    @Test
    public void cacheAble_l1ExpireModeAccess_isWiredIntoCacheSetting() {
        CacheManager cm = aspect.cacheManager;
        // 强制走"缓存不存在→创建"路径，以便捕获构建出的 CacheSetting
        when(cm.getCache(anyString())).thenReturn(null);
        when(cm.getMissingCache(anyString(), any())).thenReturn(cache);

        AccessModeService proxy = proxy(new AccessModeService());
        proxy.read();

        ArgumentCaptor<CacheSetting> captor = ArgumentCaptor.forClass(CacheSetting.class);
        verify(cm).getMissingCache(eq("am"), captor.capture());
        assertThat(captor.getValue().getL1CacheSetting().getCacheExpireModeEnum())
                .isEqualTo(CacheExpireModeEnum.ACCESS);
    }
}
