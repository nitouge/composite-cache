package io.github.nitouge.cache.autoconfigure;

import io.github.nitouge.cache.annotation.aspect.BatchCacheAspect;
import io.github.nitouge.cache.annotation.aspect.CompositeAspect;
import io.github.nitouge.cache.annotation.key.CacheKeyGenerator;
import io.github.nitouge.cache.annotation.key.CacheKeyGeneratorFactory;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.api.CacheTemplate;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.consistency.CacheDeleteCompensation;
import io.github.nitouge.cache.core.consistency.MasterSlaveConsistencyHandler;
import io.github.nitouge.cache.core.consts.CacheConsts;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import io.github.nitouge.cache.core.health.CacheHealthIndicator;
import io.github.nitouge.cache.core.management.CompositeCacheEndpoint;
import io.github.nitouge.cache.core.management.CompositeCacheManager;
import io.github.nitouge.cache.core.metrics.CacheMetrics;
import io.github.nitouge.cache.core.metrics.CacheMetricsRecorder;
import io.github.nitouge.cache.core.metrics.CacheStatisticsAggregator;
import io.github.nitouge.cache.core.metrics.CompositeCacheMetricsRecorder;
import io.github.nitouge.cache.core.metrics.LogStatisticsReporter;
import io.github.nitouge.cache.core.metrics.NoOpCacheMetricsRecorder;
import io.github.nitouge.cache.core.support.penetration.CacheBloomFilter;
import io.github.nitouge.cache.core.support.penetration.GuavaCacheBloomFilter;
import io.github.nitouge.cache.core.template.DefaultCacheTemplate;
import io.github.nitouge.cache.core.util.RandomUtil;
import io.github.nitouge.cache.core.validation.CacheConfigValidator;
import io.github.nitouge.cache.prop.CompositeCacheProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.spring.starter.RedissonAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;


@AutoConfiguration
@EnableAspectJAutoProxy
@AutoConfigureAfter({
        RedissonAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration.class,
        org.springframework.boot.actuate.autoconfigure.metrics.export.prometheus.PrometheusMetricsExportAutoConfiguration.class
})
@EnableConfigurationProperties(CompositeCacheProperties.class)
@ConditionalOnProperty(prefix = "composite-cache", name = "enabled", havingValue = "true")
@Slf4j
@RequiredArgsConstructor
public class CompositeCacheConfiguration {

    private final CompositeCacheProperties properties;

    /**
     * 自定义缓存配置
     */
    @Bean
    @ConditionalOnMissingBean(CacheConfig.class)
    public CacheConfig cacheConfig(
            Environment environment,
            @Autowired(required = false) RedissonClient redissonClient) {

        // 如果没有配置，创建默认配置
        CacheConfig config = properties.getConfig();
        if (config == null) {
            config = new CacheConfig();
        }

        // 设置实例ID（= msgSrc，用于过滤本实例自身广播的缓存同步消息，必须全局唯一）
        try {
            String ip = InetAddress.getLocalHost().getHostAddress();
            String port = environment.getProperty("server.port");
            String instanceId;
            if (port != null && !port.isEmpty()) {
                instanceId = CacheConsts.CACHE_INSTANCE_PREFIX + ip + ":" + port;
            } else {
                // 非 Web / 未配置 server.port 时 port 为 null，若拼成 "ip:null" 则同主机多实例会得到相同 instanceId，
                // 导致彼此把对方广播的 L1 失效消息误判为"自己发的"而跳过（CacheMessageListener 按 msgSrc 自过滤）
                // → 跨节点 L1 失效不生效、读到陈旧值。故追加随机后缀保证唯一（仍保留 ip 便于诊断）。
                instanceId = CacheConsts.CACHE_INSTANCE_PREFIX + ip + ":" + RandomUtil.getUUID();
            }
            config.setInstanceId(instanceId);
        } catch (Exception e) {
            // 解析本机 IP 失败时，保留 CacheConfig 构造期生成的唯一默认 instanceId（prefix + UUID），不覆写
            log.warn("Failed to resolve instanceId from host/port, keep default unique instanceId", e);
        }

        // 没有 RedissonClient 则自动降级为 L1：L2 由 Redisson 实现，必须存在 RedissonClient
        // （此前误把 RedisTemplate 也算作"有 Redis"，会导致只有 RedisTemplate 时不降级、但 L2 又建不出 → NPE）
        if (redissonClient == null) {
            CacheModeEnum originalMode = config.getCacheMode();
            if (CacheModeEnum.L2 == originalMode || CacheModeEnum.L1_L2 == originalMode) {
                log.warn("No RedissonClient found, switching cache mode from {} to L1 (L2 requires Redisson)", originalMode);
                config.setCacheMode(CacheModeEnum.L1);
            }
        }

        // 验证配置
        CacheConfigValidator.validate(config);
        log.info("Cache configuration validated successfully");

        return config;
    }

    /**
     * 定义 CacheManager - 统一创建方式
     *
     * <p>说明：
     * <ul>
     *   <li>存在 RedissonClient → L1 + L2（L2 基于 Redisson 实现）</li>
     *   <li>无 RedissonClient → 仅 L1</li>
     * </ul>
     *
     * <p>注意：使用 @Autowired(required = false) 来避免 Bean 初始化顺序问题
     */
    @Bean
    @ConditionalOnMissingBean(CacheManager.class)
    public CacheManager cacheManager(CacheConfig cacheConfig,
                                     @Autowired(required = false) RedissonClient redissonClient,
                                     @Autowired(required = false) CacheMetricsRecorder metricsRecorder,
                                     @Autowired(required = false) CacheDeleteCompensation deleteCompensation,
                                     @Autowired(required = false) CacheBloomFilter bloomFilter) {

        if (redissonClient != null) {
            log.info("Creating CacheManager with RedissonClient");
        } else {
            log.warn("Creating CacheManager with L1 cache only (no RedissonClient found)");
        }

        CompositeCacheManager cacheManager = new CompositeCacheManager(cacheConfig, redissonClient);

        if (metricsRecorder != null) {
            cacheManager.setMetricsRecorder(metricsRecorder);
            log.info("CacheMetricsRecorder injected into CacheManager");
        }

        // 一致性：延迟双删调度器（仅在开启时创建，守护线程）
        CacheConfig.ConsistencyConfig consistency = cacheConfig.getConsistency();
        if (consistency != null && consistency.isDelayedDoubleDelete()) {
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
                Thread t = new Thread(r, "cache-consistency");
                t.setDaemon(true);
                return t;
            });
            cacheManager.setConsistencyScheduler(scheduler);
            log.info("Delayed double delete enabled, consistency scheduler created");
        }

        // 一致性：删除失败补偿（setter 注入打破与 CacheManager 的循环依赖）
        if (deleteCompensation != null) {
            deleteCompensation.setCacheManager(cacheManager);
            cacheManager.setDeleteCompensation(deleteCompensation);
            log.info("Delete compensation wired into CacheManager");
        }

        // 防穿透：布隆过滤器（opt-in）
        if (bloomFilter != null) {
            cacheManager.setBloomFilter(bloomFilter);
            log.info("Bloom filter wired into CacheManager");
        }

        return cacheManager;
    }

    /**
     * 缓存切面（普通注解）
     */
    @Bean
    @ConditionalOnMissingBean(CompositeAspect.class)
    public CompositeAspect compositeAspect() {
        log.info("Enabling CompositeAspect for @CacheAble, @CachePut, @CacheEvict, @Caches");
        return new CompositeAspect();
    }

    /**
     * 批量缓存切面
     */
    @Bean
    @ConditionalOnMissingBean(BatchCacheAspect.class)
    public BatchCacheAspect batchCacheAspect() {
        log.info("Enabling BatchCacheAspect for @BatchCacheAble");
        return new BatchCacheAspect();
    }

    /**
     * 缓存Key生成器（默认使用CUSTOM策略）
     */
    @Bean
    @ConditionalOnMissingBean(CacheKeyGenerator.class)
    public CacheKeyGenerator cacheKeyGenerator() {
        String strategy = properties.getKeyGeneratorStrategy();
        if (strategy == null) {
            strategy = "CUSTOM";
        }

        CacheKeyGenerator generator;
        switch (strategy.toUpperCase()) {
            case "DEFAULT":
                generator = CacheKeyGeneratorFactory.getDefault();
                log.info("Using DEFAULT CacheKeyGenerator");
                break;
            case "SMART":
                generator = CacheKeyGeneratorFactory.getSmart();
                log.info("Using SMART CacheKeyGenerator");
                break;
            case "CUSTOM":
            default:
                generator = CacheKeyGeneratorFactory.getCustom();
                log.info("Using CUSTOM CacheKeyGenerator");
                break;
        }

        return generator;
    }

    /**
     * 监控支持
     */
    @Bean
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(name = "composite-cache.metrics.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(CacheMetrics.class)
    public CacheMetrics cacheMetrics(MeterRegistry registry) {
        log.info("Enabling cache metrics with Micrometer");
        return new CacheMetrics(registry);
    }

    /**
     * 缓存统计聚合器
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(name = "composite-cache.metrics.enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnMissingBean(CacheStatisticsAggregator.class)
    public CacheStatisticsAggregator cacheStatisticsAggregator(MeterRegistry registry) {
        log.info("Enabling cache statistics aggregator");
        int reportInterval = properties.getMetrics().getReportInterval();
        return new CacheStatisticsAggregator(registry, reportInterval, new LogStatisticsReporter());
    }

    /**
     * 统一指标记录器（唯一口径）：组合可用的 CacheMetrics(Micrometer) 与 CacheStatisticsAggregator(内存+上报)，
     * 注解切面与核心层都注入它，一次记录全部生效，避免重复计数。
     *
     * <p>标记 {@code @Primary} 以便在存在多个 {@link CacheMetricsRecorder} 实现 Bean 时优先注入本组合实例。
     */
    @Bean
    @Primary
    @ConditionalOnMissingBean(value = CacheMetricsRecorder.class, ignored = {CacheMetrics.class, CacheStatisticsAggregator.class})
    public CacheMetricsRecorder cacheMetricsRecorder(ObjectProvider<CacheMetrics> cacheMetricsProvider,
                                                     ObjectProvider<CacheStatisticsAggregator> aggregatorProvider) {

        List<CacheMetricsRecorder> recorders = new ArrayList<>();
        CacheMetrics cacheMetrics = cacheMetricsProvider.getIfAvailable();
        if (cacheMetrics != null) {
            recorders.add(cacheMetrics);
        }
        CacheStatisticsAggregator aggregator = aggregatorProvider.getIfAvailable();
        if (aggregator != null) {
            recorders.add(aggregator);
        }
        if (recorders.isEmpty()) {
            log.info("No metrics backend available, using no-op metrics recorder");
            return NoOpCacheMetricsRecorder.INSTANCE;
        }
        log.info("Unified cache metrics recorder enabled, backends={}", recorders.size());
        return new CompositeCacheMetricsRecorder(recorders);
    }

    /**
     * 健康检查
     */
    @Bean
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnMissingBean(CacheHealthIndicator.class)
    public CacheHealthIndicator cacheHealthIndicator(CacheManager cacheManager) {
        log.info("Enabling cache health indicator");
        return new CacheHealthIndicator(cacheManager);
    }

    /**
     * 缓存管理端点（Actuator，id=composite-cache）。
     *
     * <p>opt-in：需引入 actuator 且<b>显式暴露</b>，例如 {@code management.endpoints.web.exposure.include=composite-cache}。
     * 提供缓存清单/命中率查看与清空、逐出 key 等运维操作。统计依赖可选的 {@link CacheStatisticsAggregator}。
     */
    @Bean
    @ConditionalOnClass(Endpoint.class)
    @ConditionalOnAvailableEndpoint(endpoint = CompositeCacheEndpoint.class)
    @ConditionalOnMissingBean
    public CompositeCacheEndpoint compositeCacheEndpoint(
            CacheManager cacheManager,
            ObjectProvider<CacheStatisticsAggregator> aggregatorProvider) {
        log.info("Enabling composite cache actuator endpoint (id=compositecache)");
        return new CompositeCacheEndpoint(cacheManager, aggregatorProvider.getIfAvailable());
    }

    /**
     * 编程式缓存门面（推荐且唯一的编程式 API，单键 + 批量；已合并原 BatchCacheOperations）
     */
    @Bean
    @ConditionalOnMissingBean(CacheTemplate.class)
    public CacheTemplate cacheTemplate(CacheManager cacheManager) {
        log.info("Enabling cache template (programmatic API)");
        return new DefaultCacheTemplate(cacheManager);
    }

    /**
     * 删除失败补偿器（opt-in：composite-cache.config.consistency.delete-compensation=true）
     *
     * <p>开启后会被自动接入 {@code CompositeCache.evict()} 的写删路径：L2 删除失败时记录，由后台任务重试。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CacheDeleteCompensation.class)
    @ConditionalOnProperty(name = "composite-cache.config.consistency.delete-compensation", havingValue = "true")
    public CacheDeleteCompensation cacheDeleteCompensation(
            CacheConfig cacheConfig,
            @Autowired(required = false) RedissonClient redissonClient) {
        CacheConfig.ConsistencyConfig consistency = cacheConfig.getConsistency();
        log.info("Enabling cache delete compensation");
        return new CacheDeleteCompensation(redissonClient,
                consistency.getDeleteCompensationMaxRetry(),
                consistency.getDeleteCompensationIntervalSeconds());
    }

    /**
     * 主从一致性处理器（opt-in：composite-cache.config.consistency.master-slave=true）
     *
     * <p>作为编程式工具供强一致读场景使用（写后短时强制读主库）。
     */
    @Bean
    @ConditionalOnMissingBean(MasterSlaveConsistencyHandler.class)
    @ConditionalOnProperty(name = "composite-cache.config.consistency.master-slave", havingValue = "true")
    public MasterSlaveConsistencyHandler masterSlaveConsistencyHandler(
            @Autowired(required = false) RedissonClient redissonClient) {
        log.info("Enabling master-slave consistency handler");
        return new MasterSlaveConsistencyHandler(redissonClient);
    }

    /**
     * 布隆过滤器（防穿透，opt-in：composite-cache.config.penetration.bloom-enabled=true）
     *
     * <p>默认本地 Guava 实现。开启后<b>仍需</b>对每个 cacheName 显式 {@code register/warmUp}
     * （装入全量合法 key）才会生效；未注册的 cacheName 不受影响。
     */
    @Bean
    @ConditionalOnMissingBean(CacheBloomFilter.class)
    @ConditionalOnProperty(name = "composite-cache.config.penetration.bloom-enabled", havingValue = "true")
    public CacheBloomFilter cacheBloomFilter(CacheConfig cacheConfig) {
        CacheConfig.PenetrationConfig penetration = cacheConfig.getPenetration();
        log.info("Enabling cache bloom filter (penetration guard, local Guava)");
        return new GuavaCacheBloomFilter(penetration.getBloomExpectedInsertions(), penetration.getBloomFpp());
    }

    /**
     * 初始化日志
     */
    @PostConstruct
    public void init() {
        log.info("Composite Cache initialized, cacheMode={}, keyGeneratorStrategy={}",
                properties.getConfig() != null ? properties.getConfig().getCacheMode() : "default",
                properties.getKeyGeneratorStrategy());
    }

}
