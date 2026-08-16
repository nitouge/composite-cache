package io.github.nitouge.cache.core.loader;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.nitouge.cache.core.api.CacheSyncPolicy;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.consts.enums.CacheSyncTypeEnum;
import io.github.nitouge.cache.core.exception.RedisTryLockFailException;
import io.github.nitouge.cache.core.wrapper.NullValueWrapper;
import io.github.nitouge.cache.core.loader.task.LoadValueTask;
import io.github.nitouge.cache.core.support.log.CacheAccessLog;
import io.github.nitouge.cache.core.loader.task.PublishMessageTask;
import io.github.nitouge.cache.core.sync.listener.CacheSyncMessage;
import io.github.nitouge.cache.core.util.NullValueConverter;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Function;

/**
 * 缓存数据加载函数
 * 
 * <p>封装L2缓存的数据加载逻辑，支持多种缓存框架：
 * <ul>
 *   <li>Caffeine.get(key, Function)</li>
 *   <li>Guava.get(key, Function)</li>
 *   <li>ConcurrentHashMap.computeIfAbsent(key, Function)</li>
 * </ul>
 * 
 * <h3>执行流程</h3>
 * <ol>
 *   <li>如果L2缓存不存在，直接执行目标方法加载数据</li>
 *   <li>如果L2缓存存在，先从缓存加载</li>
 *   <li>缓存未命中时，执行目标方法并更新缓存</li>
 *   <li>发送缓存同步消息（如果配置了同步策略）</li>
 * </ol>
 * 
 */
@Slf4j
public class ValueLoadFunction implements Function<Object, Object> {

    private final String instanceId;
    
    private final String cacheType;
    
    private final String cacheName;
    
    private final L2Cache l2Cache;
    
    private final CacheSyncPolicy<?> cacheSyncPolicy;

    /**
     * 加载数据的目标方法
     */
    private final LoadValueTask loadValueTask;
    /**
     * 是否存储空值，设置为true时，可防止缓存穿透
     */
    private final boolean allowNullValues;
    /**
     * 存放NullValue的key，用于控制NullValue对象的有效时间
     */
    private final Cache<Object, Integer> nullValueCache;

    public ValueLoadFunction(String instanceId, String cacheType, String cacheName,
                             L2Cache l2Cache, CacheSyncPolicy<?> cacheSyncPolicy, LoadValueTask loadValueTask,
                             Boolean allowNullValues, Cache<Object, Integer> nullValueCache) {
        this.instanceId = instanceId;
        this.cacheType = cacheType;
        this.cacheName = cacheName;
        this.l2Cache = l2Cache;
        this.cacheSyncPolicy = cacheSyncPolicy;
        this.loadValueTask = loadValueTask;
        this.allowNullValues = allowNullValues;
        this.nullValueCache = nullValueCache;
    }

    /**
     * 加载缓存数据
     * 
     * <p>走到此处表明L1缓存未命中，需要从L2缓存或数据源加载数据。
     * 
     * @param key 缓存key
     * @return 缓存值
     */
    @Override
    public Object apply(Object key) {
        try {
            // 场景1：L2缓存不存在，直接执行目标方法
            if (l2Cache == null) {
                return handleNoL2Cache(key);
            }

            // 场景2：没有缓存同步策略，直接从L2加载
            if (cacheSyncPolicy == null) {
                Object value = l2Cache.get(key, loadValueTask);
                return toStoreValue(key, value);
            }

            // 场景3：完整流程（L2 + 同步策略）
            return loadWithSyncPolicy(key);
            
        } catch (RedisTryLockFailException e) {
            // Redis加载数据时的重复请求，直接返回null，避免缓存NullValue
            log.warn("Redis tryLock failed, cacheName={}, key={}", cacheName, key);
            return null;
        } catch (Exception ex) {
            log.error("Failed to load cache data, cacheName={}, key={}", cacheName, key, ex);
            throw new RuntimeException("Failed to load cache data for key: " + key, ex);
        }
    }
    
    /**
     * 处理没有L2缓存的场景
     */
    private Object handleNoL2Cache(Object key) throws Exception {
        if (loadValueTask == null) {
            log.debug("L2Cache and valueLoader are null, return null, cacheName={}, key={}", cacheName, key);
            return toStoreValue(key, null);
        }
        
        CacheAccessLog.loadFromSourceNoL2(cacheName, key);
        Object value = loadValueTask.call();
        log.debug("Loaded data from target method (no L2Cache), cacheName={}, key={}", cacheName, key);
        
        // 发送缓存同步消息
        if (cacheSyncPolicy != null) {
            publishSyncMessage(key, "AfterLoadValue");
        }
        
        return toStoreValue(key, value);
    }
    
    /**
     * 使用同步策略加载数据
     */
    private Object loadWithSyncPolicy(Object key) throws Exception {
        // 包装valueLoader，以便目标方法执行完后发送同步消息
        PublishMessageTask publishMessageTask = null;
        if (loadValueTask != null) {
            publishMessageTask = new PublishMessageTask(cacheName, key, loadValueTask);
        }
        
        // 从L2缓存加载数据
        Object value = l2Cache.get(key, publishMessageTask);
        
        // 如果执行了目标方法，则发送同步消息
        if (publishMessageTask != null && publishMessageTask.isPublishMsg()) {
            // 注意：必须在L2缓存put之后再发送消息
            // 否则消费方从缓存中获取不到数据，可能导致缓存不一致
            publishSyncMessage(key, "AfterPutL2Cache");
        }
        
        // 集群环境下的特殊处理：避免缓存NullValue导致数据不一致
        if (value == null && !hasValueLoader()) {
            log.debug("ValueLoader is null and value is null, return null to avoid caching NullValue, cacheName={}, key={}",
                    cacheName, key);
            return null;
        }
        
        return toStoreValue(key, value);
    }
    
    /**
     * 检查是否有有效的valueLoader
     */
    private boolean hasValueLoader() {
        return loadValueTask != null && loadValueTask.getValueLoader() != null;
    }
    
    /**
     * 发送缓存同步消息
     */
    private void publishSyncMessage(Object key, String description) {
        try {
            CacheSyncMessage message = new CacheSyncMessage(
                    instanceId, cacheType, cacheName, 
                    CacheSyncTypeEnum.REFRESH, key, description
            );
            cacheSyncPolicy.publish(message);
        } catch (Exception e) {
            log.error("Failed to publish sync message, cacheName={}, key={}", cacheName, key, e);
        }
    }

    /**
     * 转换为存储的值
     */
    private Object toStoreValue(Object key, Object value) {
        // allowNullValues=true，且value=null，则往缓存中put一个NullValue空对象，防止请求穿透到二级缓存或者DB上
        // 注意：CaffeineCache 的定时任务检查到缓存项的值为NullValue时，会清理掉该缓存项，避免一直缓存，一定程度上解决缓存穿透的问题。
        if (this.allowNullValues && (value == null || value instanceof NullValueWrapper)) {
            if (null != this.nullValueCache) {
                log.debug("NullValueCache put, cacheName={}, key={}", cacheName, key);
                this.nullValueCache.put(key, 1);
            }
        }
        Object storeValue = NullValueConverter.toStoreValue(value, this.allowNullValues, this.cacheName);
        log.debug("storeValue, cacheName={}, key={}", cacheName, key);
        return storeValue;
    }

}
