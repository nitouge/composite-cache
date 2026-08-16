package io.github.nitouge.cache.core.provider;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L1CacheSetting;
import io.github.nitouge.cache.core.consts.enums.CacheExpireModeEnum;
import io.github.nitouge.cache.core.impl.level1.CaffeineCache;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 L1 过期模式（WRITE/ACCESS）被 {@link CaffeineCacheProvider} 真正应用到 Caffeine 的
 * expireAfterWrite / expireAfterAccess 策略（注解 expireMode 接线后端到端生效的核心依据）。
 *
 */
public class CaffeineCacheProviderExpireModeTest {

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> buildNativeCache(CacheExpireModeEnum mode) {
        CacheConfig config = new CacheConfig();
        config.getCaffeine().setManualCache(true); // 手动缓存：避免构建 LoadingCache 的 loader 依赖
        CaffeineCacheProvider provider = new CaffeineCacheProvider(config);

        L1CacheSetting l1 = new L1CacheSetting();
        l1.setMaximumSize(100);
        l1.setExpireTime(300);
        l1.setExpireTimeUnit(TimeUnit.SECONDS);
        l1.setCacheExpireModeEnum(mode);
        CacheSetting setting = new CacheSetting();
        setting.setL1CacheSetting(l1);

        L1Cache l1Cache = provider.build("test", setting);
        return (Cache<Object, Object>) ((CaffeineCache) l1Cache).getActualCache();
    }

    @Test
    public void writeMode_appliesExpireAfterWrite() {
        Cache<Object, Object> cache = buildNativeCache(CacheExpireModeEnum.WRITE);
        assertThat(cache.policy().expireAfterWrite()).isPresent();
        assertThat(cache.policy().expireAfterAccess()).isNotPresent();
    }

    @Test
    public void accessMode_appliesExpireAfterAccess() {
        Cache<Object, Object> cache = buildNativeCache(CacheExpireModeEnum.ACCESS);
        assertThat(cache.policy().expireAfterAccess()).isPresent();
        assertThat(cache.policy().expireAfterWrite()).isNotPresent();
    }
}
