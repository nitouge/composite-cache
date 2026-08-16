package io.github.nitouge.cache.core.impl;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.consistency.CacheDeleteCompensation;
import io.github.nitouge.cache.core.impl.base.AbstractAdaptingCache;
import io.github.nitouge.cache.core.metrics.CacheMetricsRecorder;
import io.github.nitouge.cache.core.metrics.NoOpCacheMetricsRecorder;
import io.github.nitouge.cache.core.support.penetration.CacheBloomFilter;
import io.github.nitouge.cache.core.support.log.CacheAccessLog;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 组合缓存实现（L1 + L2多级缓存）
 * 
 * <p>组合了一级缓存（本地缓存）和二级缓存（分布式缓存），提供高性能的多级缓存方案。
 * 
 * <h3>缓存层级</h3>
 * <ul>
 *   <li><b>L1 Cache</b>：本地缓存（Caffeine/Guava），响应速度快（微秒级），但不支持分布式</li>
 *   <li><b>L2 Cache</b>：分布式缓存（Redis），支持多实例共享，响应速度较慢（毫秒级）</li>
 * </ul>
 * 
 * <h3>查询流程</h3>
 * <pre>
 * 1. 先查询L1缓存，命中则直接返回（最快）
 * 2. L1未命中，查询L2缓存
 * 3. L2命中，将数据同步到L1，然后返回
 * 4. L2未命中，执行valueLoader加载数据，同时写入L1和L2
 * </pre>
 * 
 * <h3>更新/删除顺序</h3>
 * <ul>
 *   <li><b>更新</b>：先更新L2，再更新L1（保证分布式一致性）</li>
 *   <li><b>删除</b>：先删除L2，再删除L1（避免从L2重新加载脏数据到L1）</li>
 * </ul>
 * 
 * <h3>性能特点</h3>
 * <ul>
 *   <li>L1命中率高时，性能提升200-1000倍（相比直接查DB）</li>
 *   <li>L2命中率高时，性能提升50-100倍</li>
 *   <li>批量操作性能提升3-10倍（相比逐个查询）</li>
 * </ul>
 * 
 * @see L1Cache 一级缓存接口
 * @see L2Cache 二级缓存接口
 */
@Slf4j
public class CompositeCache extends AbstractAdaptingCache implements Cache {

    /**
     * 一级缓存
     */
    @Getter
    private final L1Cache l1Cache;

    /**
     * 二级缓存
     */
    @Getter
    private final L2Cache l2Cache;

    /**
     * 统一指标记录器（唯一记录点）。默认空实现，由 CacheManager 注入实际 recorder。
     */
    @Setter
    private CacheMetricsRecorder metricsRecorder = NoOpCacheMetricsRecorder.INSTANCE;

    /**
     * 一致性调度器（可选）：用于延迟双删。由 CacheManager 注入，关闭一致性能力时为 null。
     */
    @Setter
    private ScheduledExecutorService consistencyScheduler;

    /**
     * 删除失败补偿器（可选）：evict 时 L2 删除失败则记录，由其后台任务重试。
     */
    @Setter
    private CacheDeleteCompensation deleteCompensation;

    /**
     * 布隆过滤器（可选）：防穿透前置拦截。由 CacheManager 注入；未配置或未对该 cacheName 注册时不生效。
     */
    @Setter
    private CacheBloomFilter bloomFilter;


    public CompositeCache(String cacheName, CacheConfig cacheConfig, L1Cache l1Cache, L2Cache l2Cache) {
        super(cacheName, cacheConfig);
        this.l1Cache = l1Cache;
        this.l2Cache = l2Cache;
        if (l1Cache.isLoadingCache()) {
            // 设置level2Cache到CustomCacheLoader中，以便CacheLoader中直接操作level2Cache
            l1Cache.getCacheLoader().setL2Cache(l2Cache);
        }
    }

    @Override
    public String getCacheType() {
        return l1Cache.getCacheType() + " + " + l2Cache.getCacheType();
    }

    @Override
    public CompositeCache getActualCache() {
        return this;
    }

    @Override
    public Object get(Object key) {
        long startTime = System.nanoTime();
        Object value = null;
        try {
            // 是否开启一级缓存
            // L1为LoadingCache，则会在CacheLoader中对L2进行了存取操作，所以此处直接返回
            if (l1Cache.isLoadingCache()) {
                // 先用 getIfPresent 探测真实的 L1 命中（不触发加载），
                // 避免把"由加载器从 L2/DB 加载"误记为 L1 命中而虚高命中率。
                boolean l1Hit = l1Cache.getIfPresent(key) != null;
                value = l1Cache.get(key);
                if (l1Hit) {
                    recordHit("L1");
                    CacheAccessLog.hitL1(getCacheName(), key);
                }
                // L1 未命中时不在此打来源：加载器下沉到 L2，由 RedissonRBucketCache 如实打印"L2 命中 / 回源加载"
                // L1 未命中时不在此记录：加载器会下沉到 L2（RedissonRBucketCache.get(key,callable)），
                // 由 L2 层如实记录"L2 命中 / 回源未命中"，避免组合层笼统记 miss 而漏掉 L2 命中。
                recordMetrics("get", startTime, value != null);
                return value;
            }
            // 从L1获取缓存。命中判定：非空值直接命中；值为 null 时再用 isExists 区分
            // “命中空值(NullValue 占位)”与“真未命中”——使 L1 缓存的空值也能短路、不穿透到 L2。
            value = l1Cache.get(key);
            boolean l1Present = value != null || l1Cache.isExists(key);
            if (l1Present) {
                if (log.isDebugEnabled()) {
                    log.debug("l1Cache get cache, cacheName={}, key={}", this.getCacheName(), key);
                }
                recordHit("L1");
                CacheAccessLog.hitL1(getCacheName(), key);
                recordMetrics("get", startTime, true);
                return value;
            }
            // 从L2获取缓存：同理，非空值直接命中，值为 null 时用 isExists 区分“命中空值”与“真未命中”
            value = l2Cache.get(key);
            boolean l2Present = value != null || l2Cache.isExists(key);
            if (l2Present) {
                if (log.isDebugEnabled()) {
                    log.debug("l2Cache get cache and put in l1Cache, cacheName={}, key={}", this.getCacheName(), key);
                }
                l1Cache.put(key, value);
                recordHit("L2");
                CacheAccessLog.hitL2(getCacheName(), key);
                recordMetrics("get", startTime, true);
            } else {
                recordMiss();
                CacheAccessLog.miss(getCacheName(), key);
                recordMetrics("get", startTime, false);
            }
            return value;
        } catch (Exception e) {
            recordMetrics("get", startTime, false);
            throw e;
        }
    }

    @Override
    public Object getIfPresent(Object key) {
        // 从L1获取缓存。非空值直接命中；值为 null 时用 isExists 区分“命中空值(NullValue)”与“真未命中”，
        // 使 L1 空值也短路、不穿透到 L2。
        Object value = l1Cache.getIfPresent(key);
        boolean l1Present = value != null || l1Cache.isExists(key);
        if (l1Present) {
            if (log.isDebugEnabled()) {
                log.debug("l1Cache hit cache, cacheName={}, key={}", this.getCacheName(), key);
            }
            recordHit("L1");
            CacheAccessLog.hitL1(getCacheName(), key);
            return value;
        }
        // 从L2获取缓存：非空值直接命中，值为 null 时用 isExists 区分“命中空值”与“真未命中”
        value = l2Cache.getIfPresent(key);
        boolean l2Present = value != null || l2Cache.isExists(key);
        if (l2Present) {
            if (log.isDebugEnabled()) {
                log.debug("l2Cache hit cache and put in l1Cache, cacheName={}, key={}", this.getCacheName(), key);
            }
            l1Cache.put(key, value);
            recordHit("L2");
            CacheAccessLog.hitL2(getCacheName(), key);
        } else {
            recordMiss();
            CacheAccessLog.miss(getCacheName(), key);
        }
        return value;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        long startTime = System.nanoTime();
        // 先用 getIfPresent 探测真实的 L1 命中（不触发加载）
        boolean isL1Hit = l1Cache.getIfPresent(key) != null;

        // 调用 L1 的 get(key, callable)：未命中时由加载器解析 L2/回源 DB
        // 防穿透：对已注册布隆过滤器的 cacheName，包装 valueLoader，在回源 DB 前拦截"一定不存在"的 key
        T result = l1Cache.get(key, wrapWithBloom(key, valueLoader));

        if (isL1Hit) {
            recordHit("L1");
            CacheAccessLog.hitL1(getCacheName(), key);
        } else if (!l1Cache.isLoadingCache()) {
            // 非加载缓存：valueLoader 直达 DB、未经过 L2 加载器路径，未命中在此记录。
            recordMiss();
        }
        // 加载缓存模式下，L1 未命中由 L2 层（加载器下沉到 RedissonRBucketCache.get(key,callable)）
        // 如实记录"L2 命中 / 回源未命中"，组合层不再重复记录，避免漏掉 L2 命中或重复计数。
        recordMetrics("get", startTime, result != null);

        return result;
    }

    @Override
    public void put(Object key, Object value) {
        // 【重要】先更新L2，再更新L1
        // 原因：保证分布式环境下的数据一致性，L2是多实例共享的
        l2Cache.put(key, value);
        l1Cache.put(key, value);
        // 维护布隆过滤器：写入真实值时标记 key 存在，避免后续被误拦
        if (value != null && bloomFilter != null && bloomFilter.isRegistered(getCacheName())) {
            bloomFilter.put(getCacheName(), key);
        }
    }

    @Override
    public void evict(Object key) {
        if (log.isDebugEnabled()) {
            log.debug("evict cache, cacheName={}, key={}", this.getCacheName(), key);
        }
        // 【重要】先删除L2，再删除L1
        // 原因：避免短时间内，如果先删除L1，其他请求会从L2重新加载脏数据到L1
        // 场景：线程A删除L1 -> 线程B查询，L1未命中，从L2加载脏数据 -> 线程A删除L2（晚了）
        evictL2WithCompensation(key);
        l1Cache.evict(key);

        // 记录驱逐
        metricsRecorder.recordEviction(getCacheName());

        // 延迟双删（可选，缓解同步/主从延迟导致的脏数据）
        scheduleDelayedDoubleDelete(key);
    }

    /**
     * 删除 L2 缓存；若开启删除失败补偿且删除抛异常，则记录失败交由补偿器重试，否则按原行为抛出。
     */
    private void evictL2WithCompensation(Object key) {
        boolean compensationEnabled = deleteCompensation != null
                && cacheConfig.getConsistency() != null
                && cacheConfig.getConsistency().isDeleteCompensation();
        if (!compensationEnabled) {
            l2Cache.evict(key);
            return;
        }
        try {
            l2Cache.evict(key);
        } catch (RuntimeException e) {
            log.error("L2 evict failed, record for compensation, cacheName={}, key={}", getCacheName(), key, e);
            deleteCompensation.recordDeleteFailure(getCacheName(), key);
        }
    }

    /**
     * 若开启延迟双删，则在配置的延迟后再次删除 L2 与 L1。
     */
    private void scheduleDelayedDoubleDelete(Object key) {
        CacheConfig.ConsistencyConfig consistency = cacheConfig.getConsistency();
        if (consistency == null || !consistency.isDelayedDoubleDelete() || consistencyScheduler == null) {
            return;
        }
        long delayMillis = consistency.getDelayedDoubleDeleteMillis();
        consistencyScheduler.schedule(() -> {
            try {
                evictL2WithCompensation(key);
                l1Cache.evict(key);
                if (log.isDebugEnabled()) {
                    log.debug("delayed double delete executed, cacheName={}, key={}", getCacheName(), key);
                }
            } catch (Exception e) {
                log.warn("delayed double delete failed, cacheName={}, key={}", getCacheName(), key, e);
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void clear() {
        if (log.isDebugEnabled()) {
            log.debug("clear all cache, cacheName={}", this.getCacheName());
        }
        // 【重要】先清除L2，再清除L1（原因同evict）
        l2Cache.clear();
        l1Cache.clear();
    }

    @Override
    public boolean isExists(Object key) {
        if (l1Cache.isExists(key)) {
            return true;
        }
        return l2Cache.isExists(key);
    }

    @Override
    public <K, V> Map<K, V> batchGet(Map<K, Object> keyMap, boolean returnNullValueKey) {
        return this.batchGetOrLoadFromL1L2(keyMap, null, "batchGet", returnNullValueKey);
    }

    @Override
    public <K, V> Map<K, V> batchGetOrLoad(Map<K, Object> keyMap, Function<List<K>, Map<K, V>> valueLoader, boolean returnNullValueKey) {
        return this.batchGetOrLoadFromL1L2(keyMap, valueLoader, "batchGetOrLoad", returnNullValueKey);
    }

    /**
     * 从L1和L2中批量获取缓存数据（核心方法）
     * 
     * <p>执行流程：
     * <ol>
     *   <li>从L1批量查询，获取命中的数据</li>
     *   <li>L1未命中的key，从L2批量查询</li>
     *   <li>L2命中的数据，同步到L1</li>
     *   <li>L2也未命中的key，通过valueLoader加载，并写入L1和L2</li>
     * </ol>
     * 
     * @param keyMap 缓存key集合
     * @param valueLoader 数据加载器（用于缓存未命中时加载数据）
     * @param methodName 方法名（用于日志输出）
     * @param returnNullValueKey 是否返回NullValue的key（防止缓存穿透）
     * @return 缓存数据集合
     */
    private <K, V> Map<K, V> batchGetOrLoadFromL1L2(Map<K, Object> keyMap, Function<List<K>, Map<K, V>> valueLoader, String methodName, boolean returnNullValueKey) {
        // 缓存命中列表
        Map<K, V> hitCacheMap = new HashMap<>();
        // 一级缓存未命中的key列表
        Map<K, Object> l1NotHitKeyMap = new HashMap<>();

        // 空入参直接返回
        if (keyMap == null || keyMap.isEmpty()) {
            return this.filterNullValue(hitCacheMap, returnNullValueKey);
        }

        // 一级缓存批量查询（所有 key 都走 L1；returnNullValueKey 固定为 true，不要修改，防止穿透到下一层）
        Map<K, V> l1HitMap = l1Cache.batchGet(keyMap, true);
        hitCacheMap.putAll(l1HitMap);
        recordBatchHit("L1", l1HitMap.size());
        // 过滤出一级缓存未命中的 key
        keyMap.entrySet().stream().filter(entry -> !l1HitMap.containsKey(entry.getKey())).forEach(entry -> l1NotHitKeyMap.put(entry.getKey(), entry.getValue()));
        log.debug("[CompositeCache] {} l1Cache batchGet, cacheName={}, l1NotHitKeySize={}", methodName, this.getCacheName(), l1NotHitKeyMap.size());

        // 一级缓存全部命中
        if (l1NotHitKeyMap.isEmpty()) {
            log.debug("[CompositeCache] {} l1Cache all hit, cacheName={}, keyMapSize={}", methodName, this.getCacheName(), keyMap.size());
            return this.filterNullValue(hitCacheMap, returnNullValueKey);
        }

        // 二级缓存批量查询
        Map<K, V> l2HitMap = l2Cache.batchGet(l1NotHitKeyMap, true);// 此处returnNullValueKey固定为true，不要修改防止缓存穿透

        if (l2HitMap != null && !l2HitMap.isEmpty()) {
            hitCacheMap.putAll(l2HitMap);// 合并数据
            recordBatchHit("L2", l2HitMap.size());

            // 二级缓存命中的数据回填一级缓存（业务 key -> cacheKey 用入参 keyMap 映射）
            Map<Object, V> l2HitMapTemp = l2HitMap.entrySet().stream()
                    .collect(HashMap::new, (map, entry) -> map.put(keyMap.get(entry.getKey()), entry.getValue()), HashMap::putAll);
            if (!l2HitMapTemp.isEmpty()) {
                l1Cache.batchPut(l2HitMapTemp);
                log.debug("{} l2Cache batchPut to l1Cache, cacheName={}, cacheMapSize={}", methodName, this.getCacheName(), l2HitMapTemp.size());
            }
        }

        // 一级缓存与二级缓存全部命中
        if (hitCacheMap.size() == keyMap.size()) {
            log.debug("{} l1Cache and l2Cache all hit, cacheName={}, keyMapSize={}", methodName, this.getCacheName(), keyMap.size());
            return this.filterNullValue(hitCacheMap, returnNullValueKey);
        }

        // 获取未命中二级缓存的key列表
        Map<K, Object> l2NotHitKeyMap = l1NotHitKeyMap.entrySet().stream()
                .filter(entry -> !l2HitMap.containsKey(entry.getKey()))
                .collect(HashMap::new, (map, entry) -> map.put(entry.getKey(), entry.getValue()), HashMap::putAll);
        // L1、L2 均未命中的 key 记为未命中
        recordBatchMiss(l2NotHitKeyMap.size());

        if (null == valueLoader) {
            log.debug("[CompositeCache] {} valueLoader is null, cacheName={}, hitCacheMapSize={}, l2NotHitKeySize={}", methodName, this.getCacheName(), hitCacheMap.size(), l2NotHitKeyMap.size());
            return this.filterNullValue(hitCacheMap, returnNullValueKey);
        }

        // 防穿透：对已注册布隆过滤器的 cacheName，过滤掉"一定不存在"的 key，避免批量回源 DB
        Map<K, Object> loadKeyMap = bloomFilterMissedKeys(l2NotHitKeyMap);

        // 注意：批量回源吞吐优先，不经过单 key 的分布式锁单飞（LOCK 策略）——
        // 并发的重叠批量可能各自回源一次；热点且回源昂贵的 key 建议单独走 get(key, loader)（LOCK）。
        Map<K, V> valueLoaderHitMap = this.loadAndPut(valueLoader, loadKeyMap);
        if (valueLoaderHitMap != null && !valueLoaderHitMap.isEmpty()) {
            hitCacheMap.putAll(valueLoaderHitMap);// 合并数据
            maintainBloom(valueLoaderHitMap);// 维护布隆过滤器
        }
        return this.filterNullValue(hitCacheMap, returnNullValueKey);
    }

    @Override
    public <V> void batchPut(Map<Object, V> dataMap) {
        if (dataMap == null || dataMap.isEmpty()) {
            return;
        }
        // 【重要】先写L2，再写L1（与单条 put 保持一致：L2 为多实例共享，先写 L2 保证分布式一致性）
        if (cacheConfig.getRedis().isSupportBatch()) {
            // 批量插入二级缓存（通过管道批量put）
            l2Cache.batchPut(dataMap);
        } else {
            // 循环put单个缓存，防止管道批量put长时间占用连接导致无连接可用
            log.debug("batchPut l2Cache start, cacheName={}, totalKeyMapSize={}", this.getCacheName(), dataMap.size());
            dataMap.forEach(l2Cache::put);
            log.debug("batchPut l2Cache end, cacheName={}, totalKeyMapSize={}", this.getCacheName(), dataMap.size());
        }

        // 批量插入一级缓存
        l1Cache.batchPut(new HashMap<>(dataMap));
    }

    @Override
    public <K> void batchEvict(Map<K, Object> keyMap) {
        if (keyMap == null || keyMap.isEmpty()) {
            return;
        }
        // 【重要】先删L2，再删L1（与单条 evict 保持一致：避免删L1后并发读把L2旧数据回填到L1，再删L2已晚）
        if (cacheConfig.getRedis().isSupportBatch()) {
            // 批量删除二级缓存（通过管道批量evict）
            l2Cache.batchEvict(keyMap);
        } else {
            // 循环evict单个缓存
            log.debug("batchEvict l2Cache start, cacheName={}, totalKeyMapSize={}", this.getCacheName(), keyMap.size());
            keyMap.forEach((key, cacheKey) -> l2Cache.evict(cacheKey));
            log.debug("batchEvict l2Cache end, cacheName={}, totalKeyMapSize={}", this.getCacheName(), keyMap.size());
        }

        // 批量删除一级缓存
        l1Cache.batchEvict(new HashMap<>(keyMap));

        // 记录驱逐指标（一次性累加）
        metricsRecorder.recordEviction(getCacheName(), keyMap.size());
    }

    /**
     * 记录缓存命中（带缓存级别）
     *
     * @param level 缓存级别（L1/L2），null 表示不区分
     */
    private void recordHit(String level) {
        if (level != null) {
            metricsRecorder.recordHit(getCacheName(), level);
        } else {
            metricsRecorder.recordHit(getCacheName());
        }
    }

    /**
     * 记录缓存未命中
     */
    private void recordMiss() {
        metricsRecorder.recordMiss(getCacheName());
    }

    /**
     * 批量记录命中（一次性累加 count）
     */
    private void recordBatchHit(String level, int count) {
        if (count > 0) {
            metricsRecorder.recordHit(getCacheName(), level, count);
        }
    }

    /**
     * 批量记录未命中（一次性累加 count）
     */
    private void recordBatchMiss(int count) {
        if (count > 0) {
            metricsRecorder.recordMiss(getCacheName(), count);
        }
    }

    /**
     * 记录操作指标
     */
    private void recordMetrics(String operation, long startTime, boolean success) {
        long duration = System.nanoTime() - startTime;
        metricsRecorder.recordLatency(getCacheName(), operation, duration, TimeUnit.NANOSECONDS);
    }

    // ---------- 布隆过滤器（防穿透）辅助 ----------

    /**
     * 若该 cacheName 已注册布隆过滤器，则包装 valueLoader：回源前判断 key 是否可能存在，
     * "一定不存在"则直接返回 null（拦截穿透），加载到真实值则写回过滤器。否则原样返回（零开销）。
     */
    private <T> Callable<T> wrapWithBloom(Object key, Callable<T> valueLoader) {
        if (valueLoader == null || bloomFilter == null || !bloomFilter.isRegistered(getCacheName())) {
            return valueLoader;
        }
        return () -> {
            if (!bloomFilter.mightContain(getCacheName(), key)) {
                if (log.isDebugEnabled()) {
                    log.debug("[BloomFilter] blocked penetration before DB load, cacheName={}, key={}", getCacheName(), key);
                }
                return null;
            }
            T value = valueLoader.call();
            if (value != null) {
                bloomFilter.put(getCacheName(), key);
            }
            return value;
        };
    }

    /**
     * 批量回源前用布隆过滤器过滤掉"一定不存在"的 key（仅对已注册的 cacheName 生效）。
     */
    private <K> Map<K, Object> bloomFilterMissedKeys(Map<K, Object> missedKeyMap) {
        if (bloomFilter == null || missedKeyMap.isEmpty() || !bloomFilter.isRegistered(getCacheName())) {
            return missedKeyMap;
        }
        Map<K, Object> loadKeyMap = new HashMap<>();
        int blocked = 0;
        for (Map.Entry<K, Object> entry : missedKeyMap.entrySet()) {
            if (bloomFilter.mightContain(getCacheName(), entry.getKey())) {
                loadKeyMap.put(entry.getKey(), entry.getValue());
            } else {
                blocked++;
            }
        }
        if (blocked > 0 && log.isDebugEnabled()) {
            log.debug("[BloomFilter] batch blocked {} definitely-absent keys, cacheName={}", blocked, getCacheName());
        }
        return loadKeyMap;
    }

    /**
     * 批量加载到真实值后写回布隆过滤器。
     */
    private <K, V> void maintainBloom(Map<K, V> loadedMap) {
        if (bloomFilter == null || !bloomFilter.isRegistered(getCacheName())) {
            return;
        }
        for (Map.Entry<K, V> entry : loadedMap.entrySet()) {
            if (entry.getValue() != null) {
                bloomFilter.put(getCacheName(), entry.getKey());
            }
        }
    }

}
