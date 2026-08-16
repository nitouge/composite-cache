package io.github.nitouge.cache.core.loader.task;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;

/**
 * 缓存加载任务
 *
 * <p>把"业务回源方法（{@link Callable}）"桥接到 LoadingCache 的单一 loader：
 * 每个 key 对应一个 {@code ValueLoaderTask}，{@code load(key)} 时取出并执行对应的回源逻辑。
 *
 * <p>同一 key 的并发/重复刷新单飞由底层缓存自身保证（Caffeine/Guava 的 LoadingCache 对同一 key
 * 只会有一个加载/刷新在途），本类不再自管"刷新中"标记。
 *
 */
@Slf4j
public class LoadValueTask implements Callable<Object> {

    private final String cacheName;

    private final Object key;

    @Getter
    @Setter
    private Callable<?> valueLoader;

    public LoadValueTask(String cacheName, Object key, Callable<?> valueLoader) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("cacheName cannot be null or empty");
        }
        this.cacheName = cacheName;
        this.key = key;
        this.valueLoader = valueLoader;
    }

    @Override
    public Object call() throws Exception {
        if (valueLoader == null) {
            log.warn("ValueLoader is null, skip loading, cacheName={}, key={}", cacheName, key);
            return null;
        }

        long startTime = System.currentTimeMillis();
        try {
            Object result = valueLoader.call();
            if (log.isDebugEnabled()) {
                long duration = System.currentTimeMillis() - startTime;
                log.debug("ValueLoader executed successfully, cacheName={}, key={}, duration={}ms", cacheName, key, duration);
            }
            return result;
        } catch (Exception e) {
            log.error("ValueLoader execution failed, cacheName={}, key={}", cacheName, key, e);
            throw e;
        }
    }
}
