package io.github.nitouge.cache.core.health;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.impl.CompositeCache;
import io.github.nitouge.cache.core.impl.level2.RedissonRBucketCache;
import io.github.nitouge.cache.core.support.degrade.L2CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存健康检查指示器
 *
 * <p>集成 Spring Boot Actuator，提供缓存健康状态检查。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li><b>缓存是辅助组件</b>：单个缓存探活失败/降级<b>不会</b>把整个应用 health 拉成 DOWN
 *       （否则一次 Redis 抖动会让整个应用 readiness 不可用，过于激进）。降级/异常信息体现在 details 中；
 *       仅当 {@link CacheManager} 不可用时才返回 DOWN。</li>
 *   <li><b>如实反映降级</b>：当开启 L2 运行期降级（熔断）时，{@code isExists} 探活会被熔断吞掉而恒为成功，
 *       易使健康检查“恒为 UP、掩盖 Redis 故障”。故对每个缓存额外读取其 L2 熔断器状态，
 *       OPEN/HALF_OPEN 时在 details 标注 {@code DEGRADED(L2:OPEN)}。</li>
 * </ul>
 *
 */
@Slf4j
public class CacheHealthIndicator implements HealthIndicator {

    private static final String HEALTH_CHECK_KEY = "__health_check__";

    private final CacheManager cacheManager;

    public CacheHealthIndicator(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public Health health() {
        // 仅 CacheManager 不可用才视为 DOWN（真正的基础设施故障）
        if (cacheManager == null) {
            return Health.down().withDetail("reason", "CacheManager is null").build();
        }
        try {
            Collection<String> cacheNames = cacheManager.getCacheNames();

            Map<String, Object> details = new HashMap<>();
            details.put("cacheCount", cacheNames.size());
            details.put("cacheManager", cacheManager.getClass().getSimpleName());

            Map<String, String> cacheStatus = new HashMap<>();
            int degradedCount = 0;
            for (String cacheName : cacheNames) {
                String status = probe(cacheName);
                cacheStatus.put(cacheName, status);
                if (!"UP".equals(status)) {
                    degradedCount++;
                }
            }
            details.put("cacheStatus", cacheStatus);
            if (degradedCount > 0) {
                details.put("degradedCaches", degradedCount);
            }

            // 缓存为辅助组件：即便部分缓存降级/探活失败也保持 UP（不拖垮整个应用），明细见 cacheStatus
            return Health.up().withDetails(details).build();

        } catch (Exception e) {
            log.error("Cache health check failed", e);
            return Health.down().withException(e).build();
        }
    }

    /**
     * 探测单个缓存，返回 "UP" / "ABSENT" / "DEGRADED(...)"——异常被收敛为该缓存的明细，不向上抛。
     */
    private String probe(String cacheName) {
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                return "ABSENT";
            }
            // 轻量探活（只读 isExists，不触发回源 DB）；降级开启时会被熔断吞掉
            cache.isExists(HEALTH_CHECK_KEY);
            // 即便探活“成功”，也读取 L2 熔断器真实状态，避免降级掩盖 Redis 故障
            L2CircuitBreaker.State l2 = l2DegradeState(cache);
            if (l2 != null) {
                return "DEGRADED(L2:" + l2.name() + ")";
            }
            return "UP";
        } catch (Exception e) {
            log.warn("Health check failed for cache: {}", cacheName, e);
            return "DEGRADED(" + e.getClass().getSimpleName() + ")";
        }
    }

    /**
     * 读取该缓存 L2 的运行期降级状态：未开启降级或 CLOSED 返回 null；OPEN/HALF_OPEN 返回对应状态。
     */
    private L2CircuitBreaker.State l2DegradeState(Cache cache) {
        if (cache instanceof CompositeCache) {
            L2Cache l2 = ((CompositeCache) cache).getL2Cache();
            if (l2 instanceof RedissonRBucketCache) {
                L2CircuitBreaker.State state = ((RedissonRBucketCache) l2).getL2DegradeState();
                if (state != null && state != L2CircuitBreaker.State.CLOSED) {
                    return state;
                }
            }
        }
        return null;
    }
}
