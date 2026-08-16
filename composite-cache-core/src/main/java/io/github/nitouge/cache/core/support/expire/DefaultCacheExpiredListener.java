package io.github.nitouge.cache.core.support.expire;


import lombok.extern.slf4j.Slf4j;

/**
 * 默认缓存过期监听器（一级缓存 Caffeine/Guava 的 removalListener）。
 *
 * <p><b>仅记录 L1 缓存项被移除/过期的调试日志，不联动清理 L2。</b>
 * 这是刻意的设计：L1 与 L2 各自拥有独立 TTL（L2 的 NullValue 还会叠加随机抖动以防雪崩），
 * 按设计互不级联过期；若在 L1 过期时去删 L2，反而会破坏 L2 自有 TTL 的语义，并可能在
 * REPLACED/EXPLICIT 等"非真正过期"的移除原因下误删 L2。
 *
 * <p>跨节点 L1 失效与 NullValue 簿记清理由 {@code AbstractL1Cache} 的 nullValueCache
 * removalListener + 同步策略广播负责，不在本监听器内处理。
 *
 */
@Slf4j
public class DefaultCacheExpiredListener implements CacheExpiredListener<Object, Object> {

    @Override
    public void onExpired(Object key, Object value, String removalCause) {
        if (log.isDebugEnabled()) {
            log.debug("一级缓存项已移除, removalCause={}, key={}", removalCause, key);
        }
    }
}
