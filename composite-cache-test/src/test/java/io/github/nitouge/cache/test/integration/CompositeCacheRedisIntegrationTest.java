package io.github.nitouge.cache.test.integration;

import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.consts.CacheConsts;
import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;
import io.github.nitouge.cache.core.management.CompositeCacheManager;
import io.github.nitouge.cache.core.support.penetration.GuavaCacheBloomFilter;
import io.github.nitouge.cache.core.template.DefaultCacheTemplate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 组合缓存对<b>真实 Redis（Testcontainers）</b>的端到端集成测试——覆盖单元测试用 mock RBucket 无法验证的 L2 真实行为：
 * L1+L2 回源/命中、空值防穿透、删除重载、批量只回源未命中、跨实例共享 L2、LOCK 单飞（防击穿）、
 * TTL 抖动（防雪崩）、布隆前置拦截（防穿透增强）。
 *
 * <p><b>需要 Docker</b>：用 {@code @Testcontainers(disabledWithoutDocker = true)}，无 Docker 时整类自动跳过、不影响构建。
 * 父 pom 默认 {@code skipTests=true}，本类仅在 {@code -DskipTests=false} 且有 Docker 时运行。
 *
 */
@Testcontainers(disabledWithoutDocker = true)
public class CompositeCacheRedisIntegrationTest {

    @Container
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static RedissonClient redisson;

    @BeforeAll
    static void startRedisson() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redisson = Redisson.create(config);
    }

    @AfterAll
    static void stopRedisson() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    @BeforeEach
    void flush() {
        redisson.getKeys().flushall();
    }

    // ---------- 辅助方法 ----------

    private CompositeCacheManager newManager(CacheConfig cfg) {
        CompositeCacheManager mgr = new CompositeCacheManager(cfg, redisson);
        try {
            mgr.afterPropertiesSet();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return mgr;
    }

    private DefaultCacheTemplate newTemplate(CacheConfig cfg) {
        return new DefaultCacheTemplate(newManager(cfg));
    }

    // ---------- 测试 ----------

    @Test
    void getOrLoad_loadsOnce_thenServesFromCache() {
        DefaultCacheTemplate t = newTemplate(new CacheConfig());
        AtomicInteger calls = new AtomicInteger();
        Callable<String> loader = () -> {
            calls.incrementAndGet();
            return "v";
        };

        String r1 = t.getOrLoad("u", "k1", loader);
        String r2 = t.getOrLoad("u", "k1", loader);

        assertThat(r1).isEqualTo("v");
        assertThat(r2).isEqualTo("v");
        assertThat(calls.get()).isEqualTo(1); // 第二次命中缓存，不回源
    }

    @Test
    void nullValue_preventsPenetration() {
        DefaultCacheTemplate t = newTemplate(new CacheConfig());
        AtomicInteger calls = new AtomicInteger();
        Callable<String> loader = () -> {
            calls.incrementAndGet();
            return null; // 查无结果
        };

        String r1 = t.getOrLoad("u", "missing", loader);
        String r2 = t.getOrLoad("u", "missing", loader);

        assertThat(r1).isNull();
        assertThat(r2).isNull();
        // 空值被缓存（NullValue），第二次不再回源 —— 防穿透
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void evict_forcesReload() {
        DefaultCacheTemplate t = newTemplate(new CacheConfig());
        AtomicInteger calls = new AtomicInteger();
        Callable<String> loader = () -> "v" + calls.incrementAndGet();

        assertThat((String) t.getOrLoad("u", "k", loader)).isEqualTo("v1");
        t.evict("u", "k");
        assertThat((String) t.getOrLoad("u", "k", loader)).isEqualTo("v2"); // 删除后重新回源
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void batchGetOrLoad_loadsOnlyMissingKeys() {
        DefaultCacheTemplate t = newTemplate(new CacheConfig());
        t.getOrLoad("b", "k1", () -> "v1"); // 预置 k1

        List<String> requestedToLoad = new ArrayList<>();
        Function<List<String>, Map<String, String>> loader = keys -> {
            requestedToLoad.addAll(keys);
            Map<String, String> m = new HashMap<>();
            for (String k : keys) {
                m.put(k, "loaded_" + k);
            }
            return m;
        };

        Map<String, String> res = t.batchGetOrLoad("b", Arrays.asList("k1", "k2", "k3"), loader);

        assertThat(res).containsEntry("k1", "v1").containsKeys("k2", "k3");
        // 只对未命中的 k2/k3 回源
        assertThat(requestedToLoad).containsExactlyInAnyOrder("k2", "k3");
    }

    @Test
    void l2_isSharedAcrossInstances() {
        // 实例 1 写入（L1 + L2/Redis）
        DefaultCacheTemplate t1 = newTemplate(new CacheConfig());
        t1.getOrLoad("s", "k", () -> "fromDb");

        // 实例 2：独立 L1，共享同一 Redis L2 —— 应从 L2 命中，不触发回源
        DefaultCacheTemplate t2 = newTemplate(new CacheConfig());
        String v = t2.getOrLoad("s", "k", () -> {
            throw new AssertionError("should hit L2, not load from source");
        });
        assertThat(v).isEqualTo("fromDb");
    }

    @Test
    void lockStrategy_singleFlightUnderConcurrency() throws Exception {
        CacheConfig cfg = new CacheConfig();
        cfg.getRedis().setLoadStrategy(RedisLoadStrategyEnum.LOCK);
        cfg.getRedis().setTryLock(false); // 阻塞等待，保证回源只发生一次
        DefaultCacheTemplate t = newTemplate(cfg);

        AtomicInteger calls = new AtomicInteger();
        Callable<String> slowLoader = () -> {
            calls.incrementAndGet();
            Thread.sleep(200); // 让并发线程堆积
            return "v";
        };

        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> t.getOrLoad("hot", "hk", slowLoader)));
            }
            for (Future<String> f : futures) {
                assertThat(f.get()).isEqualTo("v");
            }
        } finally {
            pool.shutdown();
        }
        // 击穿保护：N 个并发请求只回源一次
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void ttlJitter_spreadsExpiryToAvoidAvalanche() {
        CacheConfig cfg = new CacheConfig();
        cfg.getRedis().setTtlJitterRatio(0.2); // 基础 L2 TTL=300s（defaultSetting），抖动上浮 [0,20%]
        DefaultCacheTemplate t = newTemplate(cfg);

        for (int i = 0; i < 20; i++) {
            t.put("jit", "k" + i, "v" + i);
        }

        Set<Long> ttls = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            String redisKey = "jit" + CacheConsts.SPLIT_MULTI + "k" + i;
            long ttl = redisson.getBucket(redisKey).remainTimeToLive();
            if (ttl > 0) {
                ttls.add(ttl);
            }
        }
        // 抖动后各 key 的实际 TTL 被打散（不再完全相同），避免集体同时过期
        assertThat(ttls.size()).isGreaterThan(1);
    }

    @Test
    void bloomFilter_blocksUnknownKeyBeforeLoad() {
        CompositeCacheManager mgr = newManager(new CacheConfig());
        GuavaCacheBloomFilter bloom = new GuavaCacheBloomFilter(1000, 0.01);
        bloom.register("bf", 1000, 0.01);
        bloom.put("bf", "known"); // 仅预热 "known"
        mgr.setBloomFilter(bloom);
        DefaultCacheTemplate t = new DefaultCacheTemplate(mgr);

        AtomicInteger calls = new AtomicInteger();
        Callable<String> loader = () -> {
            calls.incrementAndGet();
            return "loaded";
        };

        // 未预热的 key 被布隆"一定不存在"拦截，不回源
        assertThat((String) t.getOrLoad("bf", "unknown", loader)).isNull();
        assertThat(calls.get()).isEqualTo(0);

        // 已预热的 key 正常回源
        assertThat((String) t.getOrLoad("bf", "known", loader)).isEqualTo("loaded");
        assertThat(calls.get()).isEqualTo(1);
    }
}
