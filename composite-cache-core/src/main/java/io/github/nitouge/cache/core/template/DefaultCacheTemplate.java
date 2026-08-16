package io.github.nitouge.cache.core.template;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.api.CacheTemplate;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * 默认编程式缓存门面实现。
 *
 * <p>单键与批量操作均委托底层 {@link Cache}，以原始业务 key（或自定义 keyBuilder）为缓存 key，
 * 与注解 {@code keyExpr} 一致。{@link io.github.nitouge.cache.core.api.CacheService} 也构建在本门面之上。
 *
 */
@Slf4j
public class DefaultCacheTemplate implements CacheTemplate {

    private final CacheManager cacheManager;

    public DefaultCacheTemplate(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    // ===== 单键 =====

    @Override
    public <K, V> V getOrLoad(String cacheName, K key, Callable<V> loader) {
        Cache cache = getOrCreateCache(cacheName);
        if (cache == null) {
            return call(cacheName, key, loader);
        }
        return (V) cache.get(key, loader);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <K, V> V get(String cacheName, K key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return null;
        }
        return (V) cache.getIfPresent(key);
    }

    @Override
    public <K, V> void put(String cacheName, K key, V value) {
        Cache cache = getOrCreateCache(cacheName);
        if (cache == null) {
            log.warn("Cache not found: {}, skip put", cacheName);
            return;
        }
        cache.put(key, value);
    }

    @Override
    public <K> void evict(String cacheName, K key) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return;
        }
        cache.evict(key);
    }

    @Override
    public <K> boolean exists(String cacheName, K key) {
        Cache cache = cacheManager.getCache(cacheName);
        return cache != null && cache.isExists(key);
    }

    @Override
    public void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }

    // ===== 批量 =====

    @Override
    public <K, V> Map<K, V> batchGet(String cacheName, List<K> keys) {
        return batchGet(cacheName, keys, null);
    }

    @Override
    public <K, V> Map<K, V> batchGet(String cacheName, List<K> keys, Function<K, Object> keyBuilder) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return Collections.emptyMap();
        }
        return cache.batchGet(keys, keyBuilder);
    }

    @Override
    public <K, V> Map<K, V> batchGetOrLoad(String cacheName, List<K> keys, Function<List<K>, Map<K, V>> loader) {
        return batchGetOrLoad(cacheName, keys, null, loader);
    }

    @Override
    public <K, V> Map<K, V> batchGetOrLoad(String cacheName, List<K> keys, Function<K, Object> keyBuilder, Function<List<K>, Map<K, V>> loader) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }
        Cache cache = getOrCreateCache(cacheName);
        if (cache == null) {
            return loader == null ? Collections.emptyMap() : loader.apply(keys);
        }
        return cache.batchGetOrLoad(keys, keyBuilder, loader, false);
    }

    @Override
    public <K, V> void batchPut(String cacheName, Map<K, V> dataMap) {
        batchPut(cacheName, dataMap, null);
    }

    @Override
    public <K, V> void batchPut(String cacheName, Map<K, V> dataMap, Function<K, Object> keyBuilder) {
        if (dataMap == null || dataMap.isEmpty()) {
            return;
        }
        Cache cache = getOrCreateCache(cacheName);
        if (cache == null) {
            return;
        }
        cache.batchPut(dataMap, keyBuilder);
    }

    @Override
    public <K> void batchEvict(String cacheName, List<K> keys) {
        batchEvict(cacheName, keys, null);
    }

    @Override
    public <K> void batchEvict(String cacheName, List<K> keys, Function<K, Object> keyBuilder) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return;
        }
        cache.batchEvict(keys, keyBuilder);
    }

    @Override
    public <K, V, R> List<V> batchGetOrLoadWithRelated(String cacheName,
                                                       List<K> keys,
                                                       Function<V, K> keyExtractor,
                                                       Function<List<K>, List<V>> loader,
                                                       Function<List<V>, Map<K, R>> relatedLoader) {
        List<V> mainData = batchGetOrLoadList(cacheName, keys, keyExtractor, loader);
        if (mainData.isEmpty() || relatedLoader == null) {
            return mainData;
        }
        try {
            // 约定：relatedLoader 通过副作用把关联数据挂到主数据对象上；返回值仅供调用方使用
            relatedLoader.apply(mainData);
        } catch (Exception e) {
            log.error("Failed to load related data for cache: {}", cacheName, e);
        }
        return mainData;
    }

    // ===== 内部 =====

    private <K, V> V call(String cacheName, K key, Callable<V> loader) {
        try {
            return loader.call();
        } catch (Exception e) {
            throw new io.github.nitouge.cache.core.exception.CacheException(
                    cacheName, String.valueOf(key), "programmatic load failed", e);
        }
    }

    private Cache getOrCreateCache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            cache = cacheManager.getMissingCache(cacheName, CacheSetting.defaultSetting());
        }
        return cache;
    }
}
