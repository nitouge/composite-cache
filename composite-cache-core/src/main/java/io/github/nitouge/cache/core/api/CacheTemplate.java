package io.github.nitouge.cache.core.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 编程式缓存门面（推荐且唯一的编程式 API，单键 + 批量）。
 *
 * <p>解决注解方式的局限（内部自调用不走 AOP、批量循环触发多次切面、动态/复杂逻辑受限），
 * 提供以 {@code cacheName + 业务 key} 为中心的统一操作。本接口合并了原 {@code BatchCacheOperations}，
 * 同时支持两类批量风格：
 * <ul>
 *   <li><b>keyBuilder + Map 风格</b>（核心）：你的加载器直接返回 {@code Map<key,value>}，可配合自定义 keyBuilder
 *       （DTO/租户前缀）。{@link CacheService} 即构建在这组方法之上。</li>
 *   <li><b>keyExtractor + List 风格</b>（便捷）：你的加载器返回实体列表，由 {@code keyExtractor} 从实体提取 key，
 *       按入参顺序返回。以 default 方法适配到核心方法。</li>
 * </ul>
 *
 * <p>所有方法使用业务 key（或 keyBuilder 的结果）作为缓存 key，与注解 {@code keyExpr="#id"} 一致，
 * 因此编程式与注解可命中同一缓存项。
 *
 */
public interface CacheTemplate {

    // ===== 单键 =====

    /** 获取；不存在则通过 loader 回源加载、写回并返回。 */
    <K, V> V getOrLoad(String cacheName, K key, Callable<V> loader);

    /** 仅获取（不触发加载），不存在返回 null。 */
    <K, V> V get(String cacheName, K key);

    /** 写入缓存。 */
    <K, V> void put(String cacheName, K key, V value);

    /** 删除指定 key。 */
    <K> void evict(String cacheName, K key);

    /** 判断 key 是否存在。 */
    <K> boolean exists(String cacheName, K key);

    /** 清空整个缓存空间。 */
    void clear(String cacheName);

    // ===== 批量（核心：keyBuilder + Map 风格）=====
    // 注意：批量回源（batchGetOrLoad*）吞吐优先，不经过单 key 的分布式锁单飞（LOCK 策略），
    //       并发的重叠批量可能各自回源一次；热点且回源昂贵的 key 请单独走 getOrLoad（LOCK）或在 loader 内自保护。
    //       框架会切分 Redis pipeline；回源 DB 的 IN 分片需使用方保证，可用 CacheTemplate.chunkedLoad(...) 包装。

    /** 批量获取（原始业务 key）。 */
    <K, V> Map<K, V> batchGet(String cacheName, List<K> keys);

    /** 批量获取（自定义 key 构建器）。 */
    <K, V> Map<K, V> batchGet(String cacheName, List<K> keys, Function<K, Object> keyBuilder);

    /** 批量获取或加载（原始业务 key，Map 风格加载器）。 */
    <K, V> Map<K, V> batchGetOrLoad(String cacheName, List<K> keys, Function<List<K>, Map<K, V>> loader);

    /** 批量获取或加载（自定义 key 构建器，Map 风格加载器）。 */
    <K, V> Map<K, V> batchGetOrLoad(String cacheName, List<K> keys, Function<K, Object> keyBuilder, Function<List<K>, Map<K, V>> loader);

    /** 批量写入（原始业务 key）。 */
    <K, V> void batchPut(String cacheName, Map<K, V> dataMap);

    /** 批量写入（自定义 key 构建器）。 */
    <K, V> void batchPut(String cacheName, Map<K, V> dataMap, Function<K, Object> keyBuilder);

    /** 批量删除（原始业务 key）。 */
    <K> void batchEvict(String cacheName, List<K> keys);

    /** 批量删除（自定义 key 构建器）。 */
    <K> void batchEvict(String cacheName, List<K> keys, Function<K, Object> keyBuilder);

    /**
     * 级联批量加载（实体列表 + 关联数据）。
     *
     * <p>先按 keyExtractor 风格加载主数据，再调用 {@code relatedLoader} 加载关联数据；
     * 约定 {@code relatedLoader} 通过副作用把关联数据挂载到主数据对象上（返回的 Map 仅供调用方使用）。
     * 关联数据加载失败不影响主数据返回。
     *
     * <p><b>⚠️ 警告（缓存对象污染）</b>：主数据若命中 <b>L1（Caffeine）</b>，返回的是缓存中的<b>同一对象引用</b>；
     * 在 {@code relatedLoader} 里对这些主对象做 setter（挂关联数据）会<b>污染 L1 缓存</b>，且并发下非线程安全。
     * 故本方法仅适用于"被缓存对象不可变 / 关联本就是缓存值一部分"的场景。
     * <b>推荐改用 {@link #batchGetOrLoadCombined}</b>：用 assembler 组合出<b>新的 DTO</b>，不改动被缓存的主对象。
     */
    <K, V, R> List<V> batchGetOrLoadWithRelated(String cacheName,
                                                List<K> keys,
                                                Function<V, K> keyExtractor,
                                                Function<List<K>, List<V>> loader,
                                                Function<List<V>, Map<K, R>> relatedLoader);

    /**
     * 级联批量加载（<b>安全版</b>，返回组合 DTO，不改动被缓存的主对象，消除 N+1）。
     *
     * <p>流程：批量加载主数据（命中部分来自缓存）→ {@code relatedLoader} 返回 {@code 主key -> 关联数据} 的 Map
     * （<b>仅返回、不要修改主对象</b>）→ 用 {@code assembler} 把 (主对象, 关联数据) 组合成<b>新对象 T</b>。
     *
     * <p>相比 {@link #batchGetOrLoadWithRelated}：不依赖副作用，<b>不会污染 L1 缓存</b>。
     * {@code assembler} 必须构造新对象、不得 mutate 入参的主对象。关联加载失败时按"无关联"处理（assembler 收到 null），不影响主数据。
     *
     * @param relatedLoader 给定主数据列表，返回 {@code 主key -> 关联数据(R)}；R 可为关联实体、列表或任意聚合
     * @param assembler     由 (主对象 V, 该主 key 的关联数据 R) 组合出结果 T（新对象）
     */
    default <K, V, R, T> List<T> batchGetOrLoadCombined(String cacheName,
                                                        List<K> keys,
                                                        Function<V, K> keyExtractor,
                                                        Function<List<K>, List<V>> loader,
                                                        Function<List<V>, Map<K, R>> relatedLoader,
                                                        BiFunction<V, R, T> assembler) {
        List<V> mainData = batchGetOrLoadList(cacheName, keys, keyExtractor, loader);
        if (mainData.isEmpty()) {
            return Collections.emptyList();
        }
        Map<K, R> relatedMap;
        try {
            relatedMap = relatedLoader == null ? Collections.emptyMap() : relatedLoader.apply(mainData);
            if (relatedMap == null) {
                relatedMap = Collections.emptyMap();
            }
        } catch (Exception e) {
            // 关联加载失败不影响主数据：按"无关联"组合
            relatedMap = Collections.emptyMap();
        }
        List<T> result = new ArrayList<>(mainData.size());
        for (V v : mainData) {
            K k = keyExtractor.apply(v);
            result.add(assembler.apply(v, relatedMap.get(k)));
        }
        return result;
    }

    // ===== 批量（便捷：keyExtractor + List 风格，default 适配到核心方法）=====

    /**
     * 批量获取或加载，按入参顺序返回 List（加载器返回实体列表，keyExtractor 提取 key）。
     */
    default <K, V> List<V> batchGetOrLoadList(String cacheName, List<K> keys, Function<V, K> keyExtractor, Function<List<K>, List<V>> loader) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        Map<K, V> map = batchGetOrLoadAsMap(cacheName, keys, keyExtractor, loader);
        List<V> result = new ArrayList<>(keys.size());
        for (K key : keys) {
            V v = map.get(key);
            if (v != null) {
                result.add(v);
            }
        }
        return result;
    }

    /**
     * 批量获取或加载，返回 Map（加载器返回实体列表，keyExtractor 提取 key）。
     */
    default <K, V> Map<K, V> batchGetOrLoadAsMap(String cacheName, List<K> keys, Function<V, K> keyExtractor, Function<List<K>, List<V>> loader) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }
        return batchGetOrLoad(cacheName, keys, key -> key, missedKeys -> toMap(loader.apply(missedKeys), keyExtractor));
    }

    /**
     * 批量写入（实体列表，keyExtractor 提取 key）。
     */
    default <K, V> void batchPut(String cacheName, List<V> values, Function<V, K> keyExtractor) {
        if (values == null || values.isEmpty()) {
            return;
        }
        batchPut(cacheName, toMap(values, keyExtractor));
    }

    /**
     * List 转 Map（按 keyExtractor 提取 key），跳过 null。
     */
    static <K, V> Map<K, V> toMap(List<V> values, Function<V, K> keyExtractor) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<K, V> map = new LinkedHashMap<>();
        for (V v : values) {
            if (v == null) {
                continue;
            }
            K k = keyExtractor.apply(v);
            if (k != null) {
                map.put(k, v);
            }
        }
        return map;
    }

    /**
     * 分片加载（Map 风格）：把大 key 列表按 {@code chunkSize} 切片，逐片调用 {@code loader} 并合并结果。
     * 用于回源 loader 内部避免超大 {@code IN(...)}（如 MySQL/Oracle 的参数上限）。
     *
     * <p>用法：{@code missIds -> CacheTemplate.chunkedLoad(missIds, 500, chunk -> mapper.selectMapByIds(chunk))}
     */
    static <K, V> Map<K, V> chunkedLoad(List<K> keys, int chunkSize, Function<List<K>, Map<K, V>> loader) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyMap();
        }
        if (chunkSize <= 0 || keys.size() <= chunkSize) {
            Map<K, V> r = loader.apply(keys);
            return r == null ? Collections.emptyMap() : r;
        }
        Map<K, V> merged = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i += chunkSize) {
            List<K> chunk = keys.subList(i, Math.min(i + chunkSize, keys.size()));
            Map<K, V> part = loader.apply(chunk);
            if (part != null) {
                merged.putAll(part);
            }
        }
        return merged;
    }

    /**
     * 分片加载（List 风格）：把大 key 列表按 {@code chunkSize} 切片，逐片调用 {@code loader}，按片顺序拼接结果。
     *
     * <p>用法：{@code missIds -> CacheTemplate.chunkedLoadList(missIds, 500, chunk -> mapper.selectListByIds(chunk))}
     */
    static <K, V> List<V> chunkedLoadList(List<K> keys, int chunkSize, Function<List<K>, List<V>> loader) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }
        if (chunkSize <= 0 || keys.size() <= chunkSize) {
            List<V> r = loader.apply(keys);
            return r == null ? Collections.emptyList() : r;
        }
        List<V> merged = new ArrayList<>();
        for (int i = 0; i < keys.size(); i += chunkSize) {
            List<K> chunk = keys.subList(i, Math.min(i + chunkSize, keys.size()));
            List<V> part = loader.apply(chunk);
            if (part != null) {
                merged.addAll(part);
            }
        }
        return merged;
    }
}
