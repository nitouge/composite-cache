package io.github.nitouge.cache.core.api;


import io.github.nitouge.cache.core.util.NullValueConverter;

import java.util.concurrent.Callable;

/**
 * 缓存操作统一接口
 * 
 * <p>定义了缓存的基本操作（增删改查）和批量操作，支持多级缓存架构。
 * 
 * <h3>核心特性</h3>
 * <ul>
 *   <li>支持null值缓存，防止缓存穿透</li>
 *   <li>支持批量操作，提升性能（减少网络开销）</li>
 *   <li>支持自定义Key构建器，灵活处理复杂业务场景</li>
 *   <li>支持带加载器的获取（get with loader），简化使用</li>
 * </ul>
 * 
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 基本操作
 * cache.put("key1", "value1");
 * String value = (String) cache.get("key1");
 * cache.evict("key1");
 * 
 * // 批量操作
 * Map<Long, User> users = cache.batchGet(Arrays.asList(1L, 2L, 3L));
 * cache.batchPut(userMap, User::getId);
 * 
 * // 带加载器的获取
 * User user = cache.get(userId, () -> loadFromDB(userId));
 * }</pre>
 * 
 * <h3>注意事项</h3>
 * <ul>
 *   <li>缓存对象需要实现{@code Serializable}接口（分布式缓存场景）</li>
 *   <li>批量操作的Key如果是自定义DTO，必须重写{@code hashCode()}和{@code equals()}</li>
 *   <li>null值缓存需要配置{@code allow-null-values=true}</li>
 * </ul>
 *
 * @see L1Cache 一级缓存（本地缓存）
 * @see L2Cache 二级缓存（分布式缓存）
 */
public interface Cache extends CacheBatchOperations {

    /**
     * 缓存中是否允许null值
     */
    boolean isAllowNullValues();

    /**
     * 获取null值的过期时间
     */
    long getNullValueExpireTimeSeconds();

    /**
     * 获取缓存实例id
     */
    String getInstanceId();

    /**
     * 获取缓存类型
     */
    String getCacheType();

    /**
     * 获取缓存名称
     */
    String getCacheName();

    /**
     * 获取实际缓存对象
     */
    Object getActualCache();

    /**
     * 获取指定key的缓存项
     * 
     * <p><b>注意</b>：本地缓存在LoadingCache模式下，若缓存项不存在，会自动调用CacheLoader加载数据并缓存
     * 
     * @param key 缓存key，不能为null
     * @return 缓存值，如果不存在返回null（或NullValue）
     */
    Object get(Object key);

    /**
     * 获取指定key的缓存项（如果存在，则获取并返回）
     * 注：仅仅只是获取，缓存项不存在，则不会加载
     */
    default Object getIfPresent(Object key) {
        return get(key);
    }

    /**
     * 获取指定key的缓存项，并返回指定类型的返回值
     */
    default <T> T get(Object key, Class<T> type) {
        Object value = get(key);
        if (null == value) {
            return null;
        }
        if (type != null && !type.isInstance(value)) {
            throw new IllegalStateException("Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    /**
     * 获取指定key的缓存项，如果缓存项不存在则通过{@code valueLoader}获取值
     * <p>
     * 含义：如果已缓存，则返回；否则，则创建、缓存并返回
     *
     * @see LoadFunction#apply(Object)
     */
    <T> T get(Object key, Callable<T> valueLoader);

    /**
     * 设置指定key的缓存项
     */
    void put(Object key, Object value);

    /**
     * 如果指定的key不存在，则设置缓存项，如果存在，则返回存在的值。
     *
     * <p><b>注意：本默认实现是"读后写"，非原子</b>——组合缓存层无法跨 L1+L2 做 CAS。
     * 并发场景下可能有多个线程同时判定为"不存在"而都写入；需要严格原子语义时，请直接对 L2
     * （{@code RedissonRBucketCache} 基于 Redisson {@code trySet}）操作，或改用 {@link #get(Object, Callable)} 单飞加载。
     *
     * <p>用 {@link #isExists(Object)} 判断存在性，使已缓存的 NullValue（空值占位，防穿透）也视为"已存在"、
     * 不被覆盖；返回的"现有值"对空值占位为 {@code null}。
     *
     * @see #put(Object, Object)
     */
    default Object putIfAbsent(Object key, Object value) {
        if (isExists(key)) {
            return get(key);
        }
        put(key, value);
        return null;
    }

    /**
     * 从存储值解析为具体值
     */
    default Object fromCacheValue(Object value) {
        return NullValueConverter.fromStoreValue(value, this.isAllowNullValues());
    }

    /**
     * 转换为存储值
     */
    default Object toCacheValue(Object value) {
        return NullValueConverter.toStoreValue(value, this.isAllowNullValues(), this.getCacheName());
    }

    /**
     * 删除指定的缓存项（如果存在）
     */
    void evict(Object key);

    /**
     * 删除所有缓存项
     */
    void clear();

    /**
     * 检查key是否存在
     *
     * @return true 表示存在，false 表示不存在
     */
    boolean isExists(Object key);

}
