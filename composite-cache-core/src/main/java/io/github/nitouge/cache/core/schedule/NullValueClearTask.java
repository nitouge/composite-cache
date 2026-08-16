package io.github.nitouge.cache.core.schedule;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.nitouge.cache.core.consts.CacheConsts;
import io.github.nitouge.cache.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

/**
 * Caffeine NullValue 缓存清理任务
 *
 * <p>定期清理 NullValue 缓存，防止缓存穿透。
 *
 */
@Slf4j
public class NullValueClearTask implements Runnable {

    private final String cacheName;
    private final Cache<Object, Integer> nullValueCache;

    public NullValueClearTask(String cacheName, Cache<Object, Integer> nullValueCache) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("cacheName 不能为 null 或空");
        }
        if (nullValueCache == null) {
            throw new IllegalArgumentException("nullValueCache 不能为 null");
        }
        this.cacheName = cacheName;
        this.nullValueCache = nullValueCache;
    }

    @Override
    public void run() {
        long clearBeforeSize = nullValueCache.estimatedSize();

        // 设置 trace_id，便于排查问题
        String traceId = CacheConsts.PREFIX_CLEAR_NULL_VALUE + CacheConsts.SPLIT_SINGLE + RandomUtil.getUUID();
        MDC.put(CacheConsts.SID, traceId);

        try {
            if (clearBeforeSize <= 0) {
                log.debug("[Caffeine] NullValue 缓存为空，跳过清理, cacheName={}", cacheName);
                return;
            }

            // cleanUp 会触发 expireAfterWrite 的过期淘汰和基于大小的淘汰
            nullValueCache.cleanUp();

            long clearAfterSize = nullValueCache.estimatedSize();
            long clearedCount = clearBeforeSize - clearAfterSize;

            if (clearedCount > 0) {
                log.info("[Caffeine] NullValue 缓存已清理, cacheName={}, before={}, after={}, cleared={}",
                        cacheName, clearBeforeSize, clearAfterSize, clearedCount);
            } else {
                log.debug("[Caffeine] NullValue 缓存清理完成, cacheName={}, size={}",
                        cacheName, clearAfterSize);
            }
        } catch (Exception e) {
            log.error("[Caffeine] 清理 NullValue 缓存失败, cacheName={}, beforeSize={}, currentSize={}",
                    cacheName, clearBeforeSize, nullValueCache.estimatedSize(), e);
        } finally {
            MDC.remove(CacheConsts.SID);
        }
    }
}
