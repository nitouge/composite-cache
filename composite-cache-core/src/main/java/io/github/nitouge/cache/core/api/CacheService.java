package io.github.nitouge.cache.core.api;


import java.util.List;
import java.util.Map;

/**
 * 业务维度的缓存接口（构建在 {@link CacheTemplate} 之上的“类型化门面”）。
 *
 * <p>每个业务缓存维度实现一个 {@code CacheService}，只需提供 {@link #getCacheName()}、
 * {@link #getCacheTemplate()} 以及业务加载逻辑 {@link #queryData(Object)}/{@link #queryDataList(List)}，
 * 即可获得 get/getOrLoad/put/evict/批量 等一整套缓存操作（默认方法委托给 {@link CacheTemplate}）。
 *
 * <p><b>统一说明</b>：本接口不依赖底层 {@code Cache}，全部操作经由 {@link CacheTemplate}，与注解、编程式门面共用同一套 key 口径与记录口径。
 *
 * @param <K> 缓存 key（基本类型或自定义 DTO；为 DTO 时需重写 {@link #buildCacheKey(Object)}，并实现 equals/hashCode）
 * @param <R> 缓存值类型
 */
public interface CacheService<K, R> {

    // ---------------------------------------------------
    // 第一部分：业务需实现/提供的内容
    // ---------------------------------------------------

    /**
     * 提供编程式缓存门面（通常注入框架的 {@code CacheTemplate} Bean）。
     */
    default CacheTemplate getCacheTemplate() {
        throw new UnsupportedOperationException("Method getCacheTemplate() is not implemented, please return the injected CacheTemplate bean");
    }

    /**
     * 缓存名称（缓存维度标识）
     */
    String getCacheName();

    /**
     * 构建缓存 key。
     *
     * <p>默认实现：基本类型（数字/字符串）直接返回原始 key（与注解 {@code keyExpr="#id"} 对齐，
     * 保证编程式与注解命中同一缓存项）；自定义 DTO 必须重写本方法自行拼接（可拼租户 id 等）。
     *
     * <p>注意：返回类型为 {@code Object}，不要把基本类型 key 转成 String，否则会与注解口径不一致、
     * 在本地缓存产生“同值不同类型”的重复 key。
     *
     * @return 缓存 key
     */
    default Object buildCacheKey(K key) {
        if (key instanceof CharSequence || key instanceof Number) {
            return key;
        }
        throw new IllegalStateException("key 为自定义 DTO，请重写 buildCacheKey() 构建缓存 key: " + key);
    }

    /**
     * 查询单个业务数据（缓存未命中时回源）
     */
    R queryData(K key);

    /**
     * 查询业务数据列表（批量回源），返回 Map&lt;key, value&gt;
     */
    Map<K, R> queryDataList(List<K> keyList);

    /**
     * 更新业务数据（update 或 insert）。供 {@link #update(Object, Object)} 使用。
     */
    default void updateData(K key, R value) {
        throw new UnsupportedOperationException("Method updateData() is not implemented, please implement it in your CacheService");
    }

    /**
     * 数据更新策略：先更新 DB，再删除缓存。
     */
    default void update(K key, R value) {
        this.updateData(key, value);
        this.evict(key);
    }

    // ---------------------------------------------------
    // 第二部分：缓存操作（默认方法，委托 CacheTemplate）
    // ---------------------------------------------------

    /**
     * 获取缓存（仅获取，不触发加载）
     */
    default R get(K key) {
        return this.getCacheTemplate().get(this.getCacheName(), this.buildCacheKey(key));
    }

    /**
     * 获取或加载缓存（未命中则回源并写回）
     */
    default R getOrLoad(K key) {
        return this.getCacheTemplate().getOrLoad(this.getCacheName(), this.buildCacheKey(key), () -> this.queryData(key));
    }

    /**
     * 写入缓存。
     *
     * <p>返回值即传入的 {@code value}（直通返回），不代表缓存的最终态。
     */
    default R put(K key, R value) {
        this.getCacheTemplate().put(this.getCacheName(), this.buildCacheKey(key), value);
        return value;
    }

    /**
     * 重新加载缓存（回源并覆盖）。
     *
     * <p>返回的是本次回源结果（可能为 {@code null}），写回缓存后原样返回。
     */
    default R reload(K key) {
        R value = this.queryData(key);
        this.getCacheTemplate().put(this.getCacheName(), this.buildCacheKey(key), value);
        return value;
    }

    /**
     * 删除缓存（key 维度）
     */
    default void evict(K key) {
        this.getCacheTemplate().evict(this.getCacheName(), this.buildCacheKey(key));
    }

    /**
     * 清空缓存（cacheName 维度）
     */
    default void clear() {
        this.getCacheTemplate().clear(this.getCacheName());
    }

    /**
     * 判断 key 是否存在
     */
    default boolean isExists(K key) {
        return this.getCacheTemplate().exists(this.getCacheName(), this.buildCacheKey(key));
    }

    /**
     * 批量获取
     */
    default Map<K, R> batchGet(List<K> keyList) {
        return this.getCacheTemplate().batchGet(this.getCacheName(), keyList, this::buildCacheKey);
    }

    /**
     * 批量获取或加载
     */
    default Map<K, R> batchGetOrLoad(List<K> keyList) {
        return this.getCacheTemplate().batchGetOrLoad(this.getCacheName(), keyList, this::buildCacheKey, this::queryDataList);
    }

    /**
     * 批量重新加载（回源并覆盖）。
     *
     * <p>返回的是本次批量回源结果（可能缺键），写回缓存后原样返回。
     */
    default Map<K, R> batchReload(List<K> keyList) {
        Map<K, R> value = this.queryDataList(keyList);
        this.getCacheTemplate().batchPut(this.getCacheName(), value, this::buildCacheKey);
        return value;
    }

    /**
     * 批量删除
     */
    default void batchEvict(List<K> keyList) {
        this.getCacheTemplate().batchEvict(this.getCacheName(), keyList, this::buildCacheKey);
    }
}
