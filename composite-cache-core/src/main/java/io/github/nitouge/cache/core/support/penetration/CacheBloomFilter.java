package io.github.nitouge.cache.core.support.penetration;

import java.util.Collection;

/**
 * 缓存布隆过滤器（防穿透前置拦截，可插拔 SPI）。
 *
 * <p>作用：对"一定不存在"的 key 在<b>回源 DB 之前</b>直接拦截，避免海量非法 key 穿透到 DB。
 * 与空值缓存（NullValue）互补：NullValue 能挡住重复穿透，但每个非法 key 仍占内存；
 * 布隆过滤器以极小空间挡住绝大多数非法 key，二者叠加更稳。
 *
 * <h3>重要前提</h3>
 * <p>布隆过滤器<b>只有在装入全量合法 key 后才安全</b>——否则未装入的合法 key 会被误判为"不存在"而被拦截，
 * 导致数据看起来丢失。因此本能力<b>按 cacheName 显式注册 + 预热</b>：框架只对<b>已注册</b>的 cacheName 生效，
 * 未注册的 cacheName 一律放行（不拦截、行为不变）。新建数据需调用 {@link #put} 维护过滤器
 * （框架在成功加载到真实值 / 写缓存时会自动 {@code put}）。
 *
 * <p>布隆过滤器只有"假阳性"（可能误判为存在），没有"假阴性"（{@link #put} 过的 key 一定 {@link #mightContain}）。
 *
 */
public interface CacheBloomFilter {

    /**
     * 该 cacheName 是否已注册布隆过滤器；未注册时框架不拦截、正常回源。
     */
    boolean isRegistered(String cacheName);

    /**
     * key 是否<b>可能</b>存在。
     *
     * @return false = 一定不存在（可安全拦截回源）；true = 可能存在（放行，可能有误判）。
     * 未注册该 cacheName，或 key 为 null 时返回 true（放行）。
     */
    boolean mightContain(String cacheName, Object key);

    /**
     * 标记 key 存在（加载到真实值 / 新建数据时调用），使后续 {@link #mightContain} 放行。未注册该 cacheName 则忽略。
     */
    void put(String cacheName, Object key);

    /**
     * 为某 cacheName 注册布隆过滤器（重复注册幂等）。
     *
     * @param expectedInsertions 预期插入的合法 key 数量（影响位数组大小，应略大于实际数据量）
     * @param fpp                期望误判率（{@code 0~1}，越小越准但占用越大，如 {@code 0.01}）
     */
    void register(String cacheName, long expectedInsertions, double fpp);

    /**
     * 预热：注册并写入全量合法 key（应在对外提供服务前完成）。
     *
     * @param keys 全量合法 key（容量按其大小推断）
     * @param fpp  期望误判率
     */
    default void warmUp(String cacheName, Collection<?> keys, double fpp) {
        long expected = (keys == null || keys.isEmpty()) ? 1L : keys.size();
        register(cacheName, expected, fpp);
        if (keys != null) {
            for (Object k : keys) {
                if (k != null) {
                    put(cacheName, k);
                }
            }
        }
    }
}
