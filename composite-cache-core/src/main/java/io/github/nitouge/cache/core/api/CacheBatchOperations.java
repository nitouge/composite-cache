package io.github.nitouge.cache.core.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 缓存实例级别的批量操作（{@link Cache} 的批量能力部分）。
 *
 * <p>本接口定义单个缓存实例上的批量增删查核心方法及其便捷重载，由 {@link Cache} 继承，是底层能力。
 * 面向业务的编程式批量操作请使用 {@link CacheTemplate}（在此之上的统一门面）。
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>实现类只需实现 3 个核心方法（带 {@code Map} 参数的 {@link #batchGet(Map, boolean)}、
 *       {@link #batchGetOrLoad(Map, Function, boolean)}、{@link #batchPut(Map)}、{@link #batchEvict(Map)}），
 *       其余重载通过 default 方法委托。</li>
 *   <li>核心方法声明为抽象，强制实现类提供真实语义（避免"忘记覆盖"时静默返回空结果的隐患）。</li>
 * </ul>
 *
 * <h3>性能</h3>
 * <ul>
 *   <li>批量操作可减少网络往返，Redis 实现通过 Pipeline 批量执行；本地缓存通过 Map 批量操作。</li>
 * </ul>
 *
 * @see Cache
 * @see CacheTemplate
 */
public interface CacheBatchOperations {

    // ===== 批量获取 =====

    /**
     * 批量get（简化版，使用默认key）
     *
     * @param keyList 业务维度的key集合（K可能是自定义DTO）
     */
    default <K, V> Map<K, V> batchGet(List<K> keyList) {
        return this.batchGet(keyList, null);
    }

    /**
     * 批量get（带自定义cacheKey构建器）
     *
     * @param keyList         业务维度的key集合（K可能是自定义DTO）
     * @param cacheKeyBuilder 自定义的cacheKey构建器
     */
    default <K, V> Map<K, V> batchGet(List<K> keyList, Function<K, Object> cacheKeyBuilder) {
        // 将keyList 转换为cacheKey，因K可能是自定义DTO，同时包含去重的能力
        Map<K, Object> keyMap = new HashMap<>();// <K, cacheKey>
        if (null != cacheKeyBuilder) {
            keyList.forEach(key -> keyMap.put(key, cacheKeyBuilder.apply(key)));
        } else {
            keyList.forEach(key -> keyMap.put(key, key));
        }
        return this.batchGet(keyMap);
    }

    /**
     * 批量get（不返回NullValue的key）
     *
     * @param keyMap 缓存key集合, Map<K=表示DTO或其他基本类型, Object=完整的cacheKey>
     */
    default <K, V> Map<K, V> batchGet(Map<K, Object> keyMap) {
        return this.batchGet(keyMap, false);
    }

    /**
     * 批量获取缓存数据（核心方法，实现类必须实现）。
     *
     * <p><b>性能优化</b>：批量操作可以减少网络开销。
     *
     * <h4>重要说明</h4>
     * <ol>
     *   <li>如果K是自定义DTO，必须重写{@code hashCode()}和{@code equals()}，
     *       以便后续业务逻辑中可以通过K从返回的Map中获取对应的数据</li>
     *   <li>参数{@code returnNullValueKey=true}的作用：把值为NullValue的key也包含在返回结果中，
     *       表示该key已缓存（值为null），无需继续查询下层，防止缓存穿透</li>
     * </ol>
     *
     * @param <K>                业务key类型（可以是Long、String或自定义DTO）
     * @param <V>                缓存值类型
     * @param keyMap             缓存key集合，Map<K=业务key, Object=完整的缓存key>
     * @param returnNullValueKey true表示把value=NullValue的key包含在返回结果中
     * @return 缓存数据集合，Map<K=业务key, V=缓存值>
     */
    <K, V> Map<K, V> batchGet(Map<K, Object> keyMap, boolean returnNullValueKey);

    // ===== 批量获取或加载 =====

    /**
     * 批量get或load（简化版）
     *
     * @param keyList     业务维度的key集合（K可能是自定义DTO）
     * @param valueLoader 值加载器
     */
    default <K, V> Map<K, V> batchGetOrLoad(List<K> keyList, Function<List<K>, Map<K, V>> valueLoader) {
        return this.batchGetOrLoad(keyList, null, valueLoader, false);
    }

    /**
     * 批量get或load（带cacheKey构建器）
     *
     * @param keyList         业务维度的key集合（K可能是自定义DTO）
     * @param cacheKeyBuilder 自定义的cacheKey构建器
     * @param valueLoader     值加载器
     */
    default <K, V> Map<K, V> batchGetOrLoad(List<K> keyList, Function<K, Object> cacheKeyBuilder, Function<List<K>, Map<K, V>> valueLoader) {
        return this.batchGetOrLoad(keyList, cacheKeyBuilder, valueLoader, false);
    }

    /**
     * 批量get或load（完整版，带cacheKey构建器和NullValue控制）
     *
     * @param keyList            业务维度的key集合（K可能是自定义DTO）
     * @param cacheKeyBuilder    自定义的cacheKey构建器
     * @param valueLoader        值加载器
     * @param returnNullValueKey true 表示把value=NullValue的key包含在Map中返回
     */
    default <K, V> Map<K, V> batchGetOrLoad(List<K> keyList, Function<K, Object> cacheKeyBuilder, Function<List<K>, Map<K, V>> valueLoader, boolean returnNullValueKey) {
        // 如果keyList为空，则直接返回
        if (keyList == null || keyList.isEmpty()) {
            return new HashMap<>();
        }

        // 将keyList 转换为cacheKey，因K可能是自定义DTO
        Map<K, Object> keyMap = new HashMap<>();// <K, cacheKey>
        if (null != cacheKeyBuilder) {
            keyList.forEach(key -> keyMap.put(key, cacheKeyBuilder.apply(key)));
        } else {
            keyList.forEach(key -> keyMap.put(key, key));
        }
        return this.batchGetOrLoad(keyMap, valueLoader, returnNullValueKey);
    }

    /**
     * 批量get或load（核心方法，实现类必须实现）。
     * <p>
     * 1.如果K是自定义DTO，那么必须重写hashCode()和equals()，以便后续业务逻辑中可以通过K从hitMap中获取对应的数据
     * <p>
     * 2.参数 returnNullValueKey=true 的作用：在batchGetOrLoad中调用batchGet时，把值为NullValue的key返回，表示该key存在缓存中，无需往下执行，防止缓存穿透到下层
     *
     * @param keyMap             缓存key集合, Map<K=表示DTO或其他基本类型, Object=完整的cacheKey>
     * @param valueLoader        值加载器，返回的Map<K, V>对象中的 K 必须与传入的一致
     * @param returnNullValueKey true 表示把value=NullValue的key包含在Map中返回
     */
    <K, V> Map<K, V> batchGetOrLoad(Map<K, Object> keyMap, Function<List<K>, Map<K, V>> valueLoader, boolean returnNullValueKey);

    // ===== 批量写入 =====

    /**
     * 批量put（带cacheKey构建器）
     *
     * @param dataMap         缓存数据集合（K可能是自定义DTO）
     * @param cacheKeyBuilder 自定义的cacheKey构建器
     */
    default <K, V> void batchPut(Map<K, V> dataMap, Function<K, Object> cacheKeyBuilder) {
        if (null == dataMap || dataMap.size() == 0) {
            return;
        }
        Map<Object, V> dataMapTemp = new HashMap<>();
        if (null == cacheKeyBuilder) {
            dataMap.forEach(dataMapTemp::put);
        } else {
            // 将 key 转换为cacheKey，因K可能是自定义DTO
            dataMap.forEach((key, value) -> dataMapTemp.put(cacheKeyBuilder.apply(key), value));
        }
        this.batchPut(dataMapTemp);
    }

    /**
     * 批量put（核心方法，实现类必须实现）
     *
     * @param dataMap 缓存数据集合（key为已经构建好的缓存key）
     */
    <V> void batchPut(Map<Object, V> dataMap);

    // ===== 批量删除 =====

    /**
     * 批量evict（简化版，使用默认key）
     *
     * @param keyList 业务维度的key集合（K可能是自定义DTO）
     */
    default <K> void batchEvict(List<K> keyList) {
        this.batchEvict(keyList, null);
    }

    /**
     * 批量evict（带cacheKey构建器）
     *
     * @param keyList         业务维度的key集合（K可能是自定义DTO）
     * @param cacheKeyBuilder 自定义的cacheKey构建器
     */
    default <K> void batchEvict(List<K> keyList, Function<K, Object> cacheKeyBuilder) {
        // 将keyList 转换为cacheKey，因K可能是自定义DTO，同时包含去重的能力
        Map<K, Object> keyMap = new HashMap<>();// <K, cacheKey>
        if (null != cacheKeyBuilder) {
            keyList.forEach(key -> keyMap.put(key, cacheKeyBuilder.apply(key)));
        } else {
            keyList.forEach(key -> keyMap.put(key, key));
        }
        this.batchEvict(keyMap);
    }

    /**
     * 批量evict（核心方法，实现类必须实现）
     *
     * @param keyMap 缓存key集合, Map<K=表示DTO或其他基本类型, Object=完整的cacheKey>
     */
    <K> void batchEvict(Map<K, Object> keyMap);
}
