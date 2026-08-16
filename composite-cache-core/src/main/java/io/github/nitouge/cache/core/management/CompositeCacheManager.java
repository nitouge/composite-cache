package io.github.nitouge.cache.core.management;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.api.CacheProvider;
import io.github.nitouge.cache.core.api.CacheSyncPolicy;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.consistency.CacheDeleteCompensation;
import io.github.nitouge.cache.core.impl.base.AbstractL1Cache;
import io.github.nitouge.cache.core.impl.CompositeCache;
import io.github.nitouge.cache.core.impl.level2.RedissonRBucketCache;
import io.github.nitouge.cache.core.provider.CacheProviderHolder;
import io.github.nitouge.cache.core.schedule.ExpiredCacheRefreshSupport;
import io.github.nitouge.cache.core.schedule.NullValueClearSupport;
import io.github.nitouge.cache.core.support.penetration.CacheBloomFilter;
import io.github.nitouge.cache.core.metrics.CacheMetricsRecorder;
import io.github.nitouge.cache.core.sync.listener.CacheMessageListener;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 中央缓存管理器：按 cacheName 懒加载式动态创建并缓存各 {@link Cache} 实例，
 * 并为新建缓存按其实际类型注入 metrics 记录器、一致性（延迟双删调度 + 删除补偿）与布隆过滤器等可选组件。
 *
 * <p>实现 {@link InitializingBean} 在初始化时启动 L1 的同步策略（注册 RTopic 监听 + 可能的补偿线程），
 * 实现 {@link DisposableBean} 在销毁时关闭这些后台资源，避免上下文关闭/重建时监听器与线程泄漏。
 *
 */
@Slf4j
public class CompositeCacheManager implements CacheManager, InitializingBean, DisposableBean {

    private final Map<String, Cache> cacheContainer = new ConcurrentHashMap<>(16);

    private final CacheConfig cacheConfig;

    private final CacheProviderHolder cacheProviderHolder;

    private final RedissonClient redissonClient;

    @Setter
    private CacheMetricsRecorder metricsRecorder;

    /**
     * 一致性调度器（用于延迟双删，可选）
     */
    @Setter
    private ScheduledExecutorService consistencyScheduler;

    /**
     * 删除失败补偿器（可选）
     */
    @Setter
    private CacheDeleteCompensation deleteCompensation;

    /**
     * 布隆过滤器（可选）：防穿透前置拦截
     */
    @Setter
    private CacheBloomFilter bloomFilter;

    /**
     * afterPropertiesSet 中实际启动的同步策略（注册了 RTopic 监听 + 可能的补偿调度线程）。
     * 持有引用以便在 destroy() 时关闭，避免上下文关闭/重建时监听器与线程泄漏。
     */
    private CacheSyncPolicy<?> startedSyncPolicy;

    public CompositeCacheManager(CacheConfig cacheConfig, RedissonClient redissonClient) {
        this.cacheConfig = cacheConfig;
        this.redissonClient = redissonClient;
        this.cacheProviderHolder = CacheProviderHolder.init(cacheConfig, redissonClient);
    }

    /**
     * 按需获取缓存：命中容器直接返回；未命中时，仅在配置开启 {@link CacheConfig#isDynamic() dynamic}
     * 且提供了 cacheSetting 的情况下，原子地创建新缓存并按其实际类型注入可选组件后缓存返回，否则返回 {@code null}。
     */
    @Override
    public Cache getMissingCache(String cacheName, CacheSetting cacheSetting) {
        Cache cache = cacheContainer.get(cacheName);
        if (cache != null) {
            return cache;
        }
        if (cacheSetting == null || !cacheConfig.isDynamic()) {
            return null;
        }
        return cacheContainer.computeIfAbsent(cacheName, name -> {
            Cache newCache = cacheProviderHolder.getCache(name, cacheSetting);
            if (newCache instanceof CompositeCache) {
                CompositeCache compositeCache = (CompositeCache) newCache;
                if (metricsRecorder != null) {
                    compositeCache.setMetricsRecorder(metricsRecorder);
                    // 同步注入 L2：LoadingCache 模式下加载器下沉到 L2，由 L2 记录 L2 命中/回源未命中
                    if (compositeCache.getL2Cache() instanceof RedissonRBucketCache) {
                        ((RedissonRBucketCache) compositeCache.getL2Cache()).setMetricsRecorder(metricsRecorder);
                    }
                }
                if (consistencyScheduler != null) {
                    compositeCache.setConsistencyScheduler(consistencyScheduler);
                }
                if (deleteCompensation != null) {
                    compositeCache.setDeleteCompensation(deleteCompensation);
                }
                if (bloomFilter != null) {
                    compositeCache.setBloomFilter(bloomFilter);
                }
            } else if (newCache instanceof RedissonRBucketCache) {
                // L2-only 模式：缓存本身即 L2，由其记录 L2 命中/回源未命中
                if (metricsRecorder != null) {
                    ((RedissonRBucketCache) newCache).setMetricsRecorder(metricsRecorder);
                }
            } else if (newCache instanceof AbstractL1Cache) {
                // L1-only 模式：独立 L1 缓存（无组合层），由其自身记录命中/未命中
                if (metricsRecorder != null) {
                    ((AbstractL1Cache) newCache).setMetricsRecorder(metricsRecorder);
                }
            }
            return newCache;
        });
    }

    /**
     * 返回当前已创建缓存的名称集合（只读视图，会随容器变化）。
     */
    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableCollection(cacheContainer.keySet());
    }

    /**
     * 启动 L1 provider 上的同步策略：注入全局配置与本机消息监听器（携带 instanceId 以忽略自身广播），
     * 对 Redis 系策略额外注入 Redisson 客户端，随后 {@code run()} 启动并记录引用以便 {@link #destroy()} 关闭。
     */
    @SuppressWarnings("unchecked")
    @Override
    public void afterPropertiesSet() {
        CacheProvider<?, ?> l1CacheProvider = cacheProviderHolder.getL1CacheProvider();
        if (l1CacheProvider != null) {
            CacheSyncPolicy<?> cacheSyncPolicy = l1CacheProvider.getCacheSyncPolicy();
            if (cacheSyncPolicy != null) {
                cacheSyncPolicy.setCacheConfig(cacheConfig);
                cacheSyncPolicy.setCacheMessageListener(new CacheMessageListener(cacheConfig.getInstanceId(), this));
                if (cacheSyncPolicy instanceof io.github.nitouge.cache.core.sync.RedisCacheSyncPolicy
                        || cacheSyncPolicy instanceof io.github.nitouge.cache.core.sync.EnhancedRedisCacheSyncPolicy) {
                    ((CacheSyncPolicy<RedissonClient>) cacheSyncPolicy).setActualClient(redissonClient);
                }
                cacheSyncPolicy.run();
                this.startedSyncPolicy = cacheSyncPolicy;
            }
        }
    }

    /**
     * 关闭由本管理器启动的后台资源：同步策略（RTopic 监听 + 补偿线程）与延迟双删调度器。
     * <p>由 Spring 在销毁 Bean 时自动调用，避免上下文关闭/刷新时监听器与线程泄漏。
     */
    @Override
    public void destroy() {
        if (startedSyncPolicy != null) {
            try {
                startedSyncPolicy.close();
            } catch (Exception e) {
                log.warn("Failed to close cache sync policy on shutdown", e);
            }
        }
        if (consistencyScheduler != null) {
            consistencyScheduler.shutdownNow();
        }
        // 关闭共享的刷新/NullValue 清理调度器，停止其后台守护任务，避免上下文关闭后任务残留（C14-1/C14-2）
        ExpiredCacheRefreshSupport.shutdown();
        NullValueClearSupport.shutdown();
    }
}
