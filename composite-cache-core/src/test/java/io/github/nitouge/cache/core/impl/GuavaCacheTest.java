package io.github.nitouge.cache.core.impl;

import com.google.common.cache.CacheBuilder;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.impl.level1.GuavaCache;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GuavaCache} 的单元测试。
 *
 * <p>重点验证 Guava 不允许 null 值的差异处理：框架通过 NullValueWrapper 兼容 null，
 * manual 模式下 {@code get(key, Callable)} 的单飞加载与回填、以及 allowNullValues 开关行为。
 *
 */
public class GuavaCacheTest {

    /**
     * 构建一个 manual 模式的 GuavaCache（cacheLoader=null），便于在无 Redis/无 LoadingCache 环境下测试。
     */
    private GuavaCache newManualCache(boolean allowNullValues) {
        CacheConfig cacheConfig = new CacheConfig().setAllowNullValues(allowNullValues);
        cacheConfig.getGuava().setManualCache(true);
        com.google.common.cache.Cache<Object, Object> native_ = CacheBuilder.newBuilder().maximumSize(100).build();
        return new GuavaCache("testGuava", cacheConfig, null, null, native_);
    }

    @Test
    public void putAndGet_shouldReturnStoredValue() {
        GuavaCache cache = newManualCache(true);

        cache.put("k1", "v1");

        assertThat(cache.get("k1")).isEqualTo("v1");
        assertThat(cache.getIfPresent("k1")).isEqualTo("v1");
        assertThat(cache.isExists("k1")).isTrue();
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    public void get_whenMissing_shouldReturnNull() {
        GuavaCache cache = newManualCache(true);

        assertThat(cache.get("missing")).isNull();
        assertThat(cache.isExists("missing")).isFalse();
    }

    @Test
    public void putNull_whenAllowNullValues_shouldStoreNullWrapperAndReturnNull() {
        GuavaCache cache = newManualCache(true);

        cache.put("nullKey", null);

        // 物理上存了 NullValueWrapper（防穿透），逻辑上读出来是 null
        assertThat(cache.get("nullKey")).isNull();
        assertThat(cache.isExists("nullKey")).isTrue();
    }

    @Test
    public void putNull_whenNotAllowNullValues_shouldInvalidateKey() {
        GuavaCache cache = newManualCache(false);
        cache.put("k", "v");

        cache.put("k", null);

        assertThat(cache.get("k")).isNull();
        assertThat(cache.isExists("k")).isFalse();
    }

    @Test
    public void getWithLoader_shouldLoadOnceAndCache() throws Exception {
        GuavaCache cache = newManualCache(true);
        AtomicInteger loadCount = new AtomicInteger();
        Callable<String> loader = () -> {
            loadCount.incrementAndGet();
            return "loaded";
        };

        String first = cache.get("k", loader);
        String second = cache.get("k", loader);

        assertThat(first).isEqualTo("loaded");
        assertThat(second).isEqualTo("loaded");
        // 第二次应命中缓存，loader 只执行一次
        assertThat(loadCount.get()).isEqualTo(1);
    }

    @Test
    public void getWithLoader_whenLoaderReturnsNull_shouldReturnNullWithoutThrowing() throws Exception {
        GuavaCache cache = newManualCache(true);
        Callable<String> loader = () -> null;

        // allowNullValues=true：loader 返回 null 会被包装为 NullValueWrapper，最终读出 null，不抛 InvalidCacheLoadException
        String result = cache.get("k", loader);

        assertThat(result).isNull();
        assertThat(cache.isExists("k")).isTrue();
    }

    @Test
    public void evict_shouldRemoveKey() {
        GuavaCache cache = newManualCache(true);
        cache.put("k", "v");

        cache.evict("k");

        assertThat(cache.get("k")).isNull();
        assertThat(cache.isExists("k")).isFalse();
    }

    @Test
    public void clear_shouldEmptyCache() {
        GuavaCache cache = newManualCache(true);
        cache.put("k1", "v1");
        cache.put("k2", "v2");

        cache.clear();

        assertThat(cache.size()).isEqualTo(0);
        assertThat(cache.get("k1")).isNull();
    }

    @Test
    public void batchGet_shouldReturnOnlyHitKeys() {
        GuavaCache cache = newManualCache(true);
        cache.put("ck1", "v1");
        cache.put("ck3", "v3");

        Map<String, Object> keyMap = new HashMap<>();
        keyMap.put("b1", "ck1");
        keyMap.put("b2", "ck2"); // 不存在
        keyMap.put("b3", "ck3");

        Map<String, String> result = cache.batchGet(keyMap, false);

        assertThat(result).containsEntry("b1", "v1").containsEntry("b3", "v3");
        assertThat(result).doesNotContainKey("b2");
    }

    @Test
    public void getCacheType_shouldBeGuava() {
        GuavaCache cache = newManualCache(true);
        assertThat(cache.getCacheType()).isEqualTo("GUAVA");
    }

    @Test
    public void isLoadingCache_whenManual_shouldBeFalse() {
        GuavaCache cache = newManualCache(true);
        assertThat(cache.isLoadingCache()).isFalse();
    }
}
