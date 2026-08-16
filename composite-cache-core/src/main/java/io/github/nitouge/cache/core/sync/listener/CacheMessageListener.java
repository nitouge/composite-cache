package io.github.nitouge.cache.core.sync.listener;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.api.MessageListener;
import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.consts.enums.CacheSyncTypeEnum;
import io.github.nitouge.cache.core.impl.CompositeCache;
import io.github.nitouge.cache.core.util.MdcUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 缓存消息监听器
 */
@Slf4j
public class CacheMessageListener implements MessageListener<CacheSyncMessage> {

    private final String msgSrc;

    private final CacheManager cacheManager;

    public CacheMessageListener(String msgSrc, CacheManager cacheManager) {
        this.msgSrc = msgSrc;
        this.cacheManager = cacheManager;
    }

    @Override
    public void onMessage(CacheSyncMessage message) {
        Map<String, String> oldContext = MdcUtils.beforeExecution(message.getMdcContextMap());
        try {
            Object key = message.getKey();
            String cacheName = message.getCacheName();
            CacheSyncTypeEnum cacheSyncType = message.getCacheSyncType();

            if (java.util.Objects.equals(this.msgSrc, message.getMsgSrc())) {
                log.debug("[SyncCache] don't need to process your own messages, msgSrc={}, message={}", this.msgSrc, message);
                return;
            }

            Cache cache = cacheManager.getCache(cacheName);
            if (null != cache) {
                L1Cache l1Cache;
                if (cache instanceof L1Cache) {
                    l1Cache = (L1Cache) cache;
                } else if (cache instanceof CompositeCache) {
                    l1Cache = ((CompositeCache) cache).getL1Cache();
                } else {
                    return;
                }
                switch (cacheSyncType) {
                    case EVICT:
                        l1Cache.evict(key);
                        log.info("L1 cache={} evict the key={}", cacheName, key);
                        return;
                    case CLEAR:
                        l1Cache.clear();
                        log.info("L1 cache={} clear all data", cacheName);
                        return;
                    case REFRESH:
                        if (key == null) {
                            l1Cache.refreshAll();
                        } else {
                            l1Cache.refresh(key);
                        }
                        log.info("L1 cache={} refresh the key={}", cacheName, key);
                        return;
                    default:
                        log.error("The cache sync type {} is not supported", cacheSyncType.name());
                        break;
                }
            }
        } catch (Exception e) {
            log.error("[SyncCache] deal message error, msgSrc={}", this.msgSrc, e);
        } finally {
            MdcUtils.afterExecution(oldContext);
        }
    }

}
