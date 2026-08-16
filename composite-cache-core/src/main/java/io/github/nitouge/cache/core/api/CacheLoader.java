package io.github.nitouge.cache.core.api;


import io.github.nitouge.cache.core.loader.task.LoadValueTask;

import java.util.concurrent.Callable;

/**
 * 缓存加载器
 */
public interface CacheLoader<K, V> {

    LoadValueTask getValueLoaderTask(K key);

    /**
     * 设置加载数据的处理器
     * 注：在获取缓存时动态设置valueLoader，来达到实现不同缓存调用不同的加载数据逻辑的目的。
     */
    void addValueLoaderTask(Object key, Callable<?> valueLoader);

    /**
     * 删除valueLoader
     */
    void removeValueLoaderTask(Object key);

    /**
     * 设置二级缓存
     */
    void setL2Cache(L2Cache l2Cache);

    /**
     * 设置缓存过期策略
     */
    void setCacheSyncPolicy(CacheSyncPolicy<?> cacheSyncPolicy);

    /**
     * 设置是否存储空值
     */
    void setAllowNullValues(boolean allowNullValues);

    /**
     * 存放 NullValue 的缓存，用于控制 NullValue 对象的有效时间
     */
    void setNullValueCache(Object nullValueCache);

    /**
     * 计算或检索与 {@code key} 对应的值
     */
    V load(K key);
}
