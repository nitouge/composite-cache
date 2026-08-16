package io.github.nitouge.cache.test.controller;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.api.CacheTemplate;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.impl.CompositeCache;
import io.github.nitouge.cache.core.impl.level2.RedissonRBucketCache;
import io.github.nitouge.cache.core.consts.CacheConsts;
import io.github.nitouge.cache.core.support.degrade.L2CircuitBreaker;
import io.github.nitouge.cache.core.support.penetration.CacheBloomFilter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存难题「演示端点」：穿透 / 雪崩 / 布隆 / 降级。
 *
 * <p>用编程式 {@link CacheTemplate} 配合可观测的"回源(DB)计数器"，直观展示框架对四类问题的处理效果。
 * 运行依赖本机 Redis（与 demo 应用一致，<b>非 Docker</b>）。布隆/降级需在 application.yml 开启
 * （本 demo 已设 {@code penetration.bloom-enabled=true} 与 {@code redis.degrade-enabled=true}）。
 *
 */
@RestController
@RequestMapping("/demo")
@Slf4j
public class ScenarioDemoController {

    @Autowired
    private CacheTemplate cacheTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private CacheManager cacheManager;

    /** 布隆过滤器 Bean：仅当 penetration.bloom-enabled=true 时存在。 */
    @Autowired(required = false)
    private CacheBloomFilter bloomFilter;

    /** 演示入口指引。 */
    @GetMapping
    public Map<String, Object> guide() {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("穿透(NullValue)", "GET /demo/penetration/{id}?times=5  —— 查不存在的 id 多次，只回源 1 次");
        g.put("雪崩(TTL抖动)", "GET /demo/avalanche?count=20  —— 同基础 TTL 的多 key，实际 TTL 被打散");
        g.put("布隆-预热", "POST /demo/bloom/warmup?keys=1,2,3  —— 预热合法 key");
        g.put("布隆-查询", "GET /demo/bloom/{id}  —— 未预热的 id 被拦在回源前(dbCalls=0)");
        g.put("降级-状态", "GET /demo/degrade/status  —— 查看各缓存 L2 熔断状态");
        g.put("降级-查询", "GET /demo/degrade/{id}  —— 停掉 Redis 后反复调用，熔断 OPEN 后仍由 L1/回源提供数据");
        return g;
    }

    // ---------- 穿透：NullValue 防穿透 ----------

    @GetMapping("/penetration/{id}")
    public Map<String, Object> penetration(@PathVariable Long id,
                                           @RequestParam(defaultValue = "5") int times) {
        String cacheName = "demo_penetration";
        cacheTemplate.evict(cacheName, id); // 清掉历史缓存，保证每次演示从头开始

        AtomicInteger dbCalls = new AtomicInteger();
        Callable<Object> loader = () -> {
            dbCalls.incrementAndGet();
            log.info("[penetration demo] 回源 DB，id={}（模拟查无结果）", id);
            return null; // 该 id 不存在
        };

        Object last = null;
        for (int i = 0; i < Math.max(1, times); i++) {
            last = cacheTemplate.getOrLoad(cacheName, id, loader);
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenario", "穿透 / NullValue 防穿透");
        r.put("id", id);
        r.put("queryTimes", times);
        r.put("dbCalls", dbCalls.get());
        r.put("result", last);
        r.put("explanation", "查不存在的 id 共 " + times + " 次，但只回源 DB " + dbCalls.get()
                + " 次——首次的空结果被缓存为 NullValue，后续直接命中。若无该机制，dbCalls 会等于 " + times + "。");
        return r;
    }

    // ---------- 雪崩：TTL 随机抖动 ----------

    @GetMapping("/avalanche")
    public Map<String, Object> avalanche(@RequestParam(defaultValue = "20") int count) {
        String cacheName = "demo_avalanche";
        int n = Math.min(Math.max(1, count), 200);
        for (int i = 0; i < n; i++) {
            cacheTemplate.put(cacheName, "k" + i, "v" + i); // L2 TTL=300s(默认) + 抖动
        }

        List<Long> sample = new ArrayList<>();
        Set<Long> distinct = new TreeSet<>();
        for (int i = 0; i < n; i++) {
            String redisKey = cacheName + CacheConsts.SPLIT_MULTI + "k" + i;
            long ttl = redissonClient.getBucket(redisKey).remainTimeToLive(); // 毫秒
            if (ttl > 0) {
                distinct.add(ttl);
                if (sample.size() < 10) {
                    sample.add(ttl);
                }
            }
        }

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenario", "雪崩 / TTL 抖动打散");
        r.put("keyCount", n);
        r.put("distinctTtlCount", distinct.size());
        r.put("sampleTtlMs", sample);
        r.put("explanation", n + " 个 key 基础 TTL 相同，但抖动后实际 TTL 有 " + distinct.size()
                + " 种不同取值，被打散——避免同一时刻集体过期引发回源洪峰。");
        return r;
    }

    // ---------- 布隆：回源前拦截"一定不存在"的 key ----------

    @PostMapping("/bloom/warmup")
    public Map<String, Object> bloomWarmup(@RequestParam List<Long> keys) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (bloomFilter == null) {
            r.put("error", "布隆未启用：请设置 composite-cache.config.penetration.bloom-enabled=true");
            return r;
        }
        String cacheName = "demo_bloom";
        bloomFilter.register(cacheName, 100000, 0.01);
        for (Long k : keys) {
            bloomFilter.put(cacheName, k);
        }
        r.put("scenario", "布隆 / 预热");
        r.put("cacheName", cacheName);
        r.put("warmedKeys", keys);
        r.put("explanation", "已对 cacheName=" + cacheName + " 预热 " + keys.size()
                + " 个合法 key；之后查未预热的 key 会被布隆判为'一定不存在'并拦在回源前。");
        return r;
    }

    @GetMapping("/bloom/{id}")
    public Map<String, Object> bloom(@PathVariable Long id) {
        Map<String, Object> r = new LinkedHashMap<>();
        if (bloomFilter == null) {
            r.put("error", "布隆未启用：请设置 composite-cache.config.penetration.bloom-enabled=true");
            return r;
        }
        String cacheName = "demo_bloom";
        cacheTemplate.evict(cacheName, id); // 清缓存，确保走"回源前的布隆判断"

        AtomicInteger dbCalls = new AtomicInteger();
        Callable<Object> loader = () -> {
            dbCalls.incrementAndGet();
            log.info("[bloom demo] 回源 DB，id={}", id);
            return "loaded_" + id;
        };
        Object v = cacheTemplate.getOrLoad(cacheName, id, loader);

        boolean mightContain = bloomFilter.mightContain(cacheName, id);
        r.put("scenario", "布隆 / 查询");
        r.put("id", id);
        r.put("bloomMightContain", mightContain);
        r.put("dbCalls", dbCalls.get());
        r.put("result", v);
        r.put("explanation", mightContain
                ? "布隆判定'可能存在' → 正常回源(dbCalls=1)。需先 POST /demo/bloom/warmup?keys=" + id + " 预热。"
                : "布隆判定'一定不存在' → 拦截，不回源(dbCalls=0)、直接返回 null。");
        return r;
    }

    // ---------- 降级：L2 运行期熔断 ----------

    @GetMapping("/degrade/status")
    public Map<String, Object> degradeStatus() {
        Map<String, Object> r = new LinkedHashMap<>();
        Map<String, String> states = new LinkedHashMap<>();
        for (String name : cacheManager.getCacheNames()) {
            states.put(name, l2State(name));
        }
        r.put("scenario", "降级 / L2 熔断状态");
        r.put("l2BreakerStates", states);
        r.put("note", "需 redis.degrade-enabled=true（本 demo 已开启）。停掉本机 Redis 后多次访问 /demo/degrade/{id}，"
                + "连续失败达阈值即 OPEN：读降级为仅 L1+回源、写跳过；Redis 恢复后半开试探自动闭合。");
        return r;
    }

    @GetMapping("/degrade/{id}")
    public Map<String, Object> degrade(@PathVariable Long id) {
        String cacheName = "demo_degrade";
        AtomicInteger dbCalls = new AtomicInteger();
        Callable<Object> loader = () -> {
            dbCalls.incrementAndGet();
            return "stock_" + id;
        };
        Object v = cacheTemplate.getOrLoad(cacheName, id, loader);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenario", "降级 / L2 熔断");
        r.put("id", id);
        r.put("result", v);
        r.put("dbCalls", dbCalls.get());
        r.put("l2State", l2State(cacheName));
        r.put("explanation", "停掉 Redis 后反复调用本接口：前几次触发 L2 异常并累计熔断，达到阈值后 OPEN，"
                + "之后读直接降级（不再卡 Redis 超时），数据仍由 L1/回源提供。");
        return r;
    }

    /** 读取指定缓存的 L2 熔断状态（非组合缓存/未开启降级时返回 N/A）。 */
    private String l2State(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache instanceof CompositeCache) {
            L2Cache l2 = ((CompositeCache) cache).getL2Cache();
            if (l2 instanceof RedissonRBucketCache) {
                L2CircuitBreaker.State state = ((RedissonRBucketCache) l2).getL2DegradeState();
                return state == null ? "N/A(降级未启用)" : state.name();
            }
        }
        return "N/A";
    }
}
