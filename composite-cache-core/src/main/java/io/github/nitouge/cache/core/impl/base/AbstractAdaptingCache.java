package io.github.nitouge.cache.core.impl.base;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 缓存适配器抽象基类
 * 
 * <p>提供缓存的通用实现，包括批量操作、空值处理等。
 * 
 */
@Slf4j
public abstract class AbstractAdaptingCache implements Cache {

    /**
     * 默认NullValue过期时间（秒）
     */
    private static final long DEFAULT_NULL_VALUE_EXPIRE_TIME = 60L;

    protected final CacheConfig cacheConfig;

    private final String instanceId;

    private final String cacheName;

    private final boolean allowNullValues;

    private final long nullValueExpireTimeSeconds;


    public AbstractAdaptingCache(String cacheName, CacheConfig cacheConfig) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("cacheName cannot be null or empty");
        }
        if (cacheConfig == null) {
            throw new IllegalArgumentException("cacheConfig cannot be null");
        }
        
        this.cacheConfig = cacheConfig;
        this.instanceId = cacheConfig.getInstanceId();
        this.cacheName = cacheName;
        this.allowNullValues = cacheConfig.isAllowNullValues();
        
        long configuredExpireTime = cacheConfig.getNullValueExpireTimeSeconds();
        this.nullValueExpireTimeSeconds = configuredExpireTime > 0 ? configuredExpireTime : DEFAULT_NULL_VALUE_EXPIRE_TIME;
    }

    @Override
    public boolean isAllowNullValues() {
        return this.allowNullValues;
    }

    @Override
    public long getNullValueExpireTimeSeconds() {
        return this.nullValueExpireTimeSeconds;
    }

    @Override
    public String getInstanceId() {
        return this.instanceId;
    }

    @Override
    public String getCacheName() {
        return this.cacheName;
    }

    @Override
    public <K, V> Map<K, V> batchGetOrLoad(Map<K, Object> keyMap, Function<List<K>, Map<K, V>> valueLoader, boolean returnNullValueKey) {
        // 1.从缓存批量获取（returnNullValueKey=true防止缓存穿透）
        Map<K, V> hitCacheMap = batchGet(keyMap, true);

        // 2.计算未命中的key
        Map<K, Object> missedKeys = calculateMissedKeys(keyMap, hitCacheMap);

        // 3.无valueLoader或全部命中，直接返回
        if (valueLoader == null) {
            log.debug("[{}] No valueLoader provided, return cached data only, cacheName={}, hitCount={}/{}", getSimpleName(), cacheName, hitCacheMap.size(), keyMap.size());
            return filterNullValue(hitCacheMap, returnNullValueKey);
        }

        if (missedKeys == null || missedKeys.isEmpty()) {
            log.debug("[{}] All keys hit cache, cacheName={}, count={}", getSimpleName(), cacheName, keyMap.size());
            return filterNullValue(hitCacheMap, returnNullValueKey);
        }

        // 4.加载未命中的数据
        Map<K, V> loadedData = loadAndPut(valueLoader, missedKeys);
        if (loadedData != null && !loadedData.isEmpty()) {
            hitCacheMap.putAll(loadedData);
        }
        
        return filterNullValue(hitCacheMap, returnNullValueKey);
    }
    
    /**
     * 计算未命中的key
     */
    private <K> Map<K, Object> calculateMissedKeys(Map<K, Object> keyMap, Map<K, ?> hitCacheMap) {
        return keyMap.entrySet().stream()
                .filter(entry -> !hitCacheMap.containsKey(entry.getKey()))
                .collect(HashMap::new,
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                        HashMap::putAll);
    }

    /**
     * 过滤null值
     *
     * @param hitCacheMap 缓存数据
     * @param returnNullValueKey true-保留null值的key，false-过滤掉null值的key
     * @return 过滤后的数据
     */
    protected <K, V> Map<K, V> filterNullValue(Map<K, V> hitCacheMap, boolean returnNullValueKey) {
        if (returnNullValueKey) {
            return hitCacheMap;
        }
        
        return hitCacheMap.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(HashMap::new, 
                        (map, entry) -> map.put(entry.getKey(), entry.getValue()), 
                        HashMap::putAll);
    }

    /**
     * 从数据源加载数据并缓存
     * 
     * @param valueLoader 数据加载器
     * @param missedKeys 未命中的key集合
     * @return 加载到的数据
     */
    protected <K, V> Map<K, V> loadAndPut(Function<List<K>, Map<K, V>> valueLoader, Map<K, Object> missedKeys) {
        try {
            // 1.从数据源加载
            Map<K, V> loadedData = loadDataFromSource(valueLoader, missedKeys);
            
            // 2.缓存加载到的数据
            if (loadedData != null && !loadedData.isEmpty()) {
                cacheLoadedData(missedKeys, loadedData);
            }
            
            // 3.缓存空值（防止缓存穿透）
            cacheNullValues(missedKeys, loadedData);
            
            return loadedData;
        } catch (Exception e) {
            log.error("[{}] Failed to load and cache data, cacheName={}, missedKeyCount={}", getSimpleName(), cacheName, missedKeys.size(), e);
            throw new io.github.nitouge.cache.core.exception.CacheException(
                    cacheName, 
                    missedKeys.values().toString(), 
                    "Batch get or load failed", 
                    e);
        }
    }
    
    /**
     * 从数据源加载数据
     */
    private <K, V> Map<K, V> loadDataFromSource(Function<List<K>, Map<K, V>> valueLoader, Map<K, Object> missedKeys) {
        List<K> keyList = new ArrayList<>(missedKeys.keySet());
        Map<K, V> loadedData = valueLoader.apply(keyList);
        
        if (loadedData == null || loadedData.isEmpty()) {
            log.debug("[{}] No data loaded from source, cacheName={}, missedKeyCount={}", getSimpleName(), cacheName, missedKeys.size());
        } else {
            log.debug("[{}] Data loaded from source, cacheName={}, loadedCount={}/{}", getSimpleName(), cacheName, loadedData.size(), missedKeys.size());
        }
        
        return loadedData;
    }
    
    /**
     * 缓存已加载的数据
     */
    private <K, V> void cacheLoadedData(Map<K, Object> missedKeys, Map<K, V> loadedData) {
        Map<Object, V> cacheDataMap = missedKeys.entrySet().stream()
                .filter(entry -> loadedData.containsKey(entry.getKey()))
                .collect(HashMap::new, 
                        (map, entry) -> map.put(entry.getValue(), loadedData.get(entry.getKey())), 
                        HashMap::putAll);
        
        if (!cacheDataMap.isEmpty()) {
            batchPut(cacheDataMap);
            log.debug("[{}] Cached loaded data, cacheName={}, count={}", getSimpleName(), cacheName, cacheDataMap.size());
        }
    }
    
    /**
     * 缓存空值（防止缓存穿透）
     */
    private <K, V> void cacheNullValues(Map<K, Object> missedKeys, Map<K, V> loadedData) {
        Map<Object, V> nullValueMap = new HashMap<>();
        
        missedKeys.forEach((k, cacheKey) -> {
            if (loadedData == null || !loadedData.containsKey(k)) {
                nullValueMap.put(cacheKey, null);
            }
        });
        
        if (!nullValueMap.isEmpty()) {
            batchPut(nullValueMap);
            log.debug("[{}] Cached null values to prevent penetration, cacheName={}, count={}", getSimpleName(), cacheName, nullValueMap.size());
        }
    }
    
    @Override
    public <V> void batchPut(Map<Object, V> dataMap) {
        if (dataMap == null || dataMap.isEmpty()) {
            return;
        }
        dataMap.forEach(this::put);
    }

    @Override
    public <K> void batchEvict(Map<K, Object> keyMap) {
        if (keyMap == null || keyMap.isEmpty()) {
            return;
        }
        keyMap.forEach((key, value) -> this.evict(value));
    }

    /**
     * 获取简化类名
     */
    private String getSimpleName() {
        return getClass().getSimpleName();
    }
}
