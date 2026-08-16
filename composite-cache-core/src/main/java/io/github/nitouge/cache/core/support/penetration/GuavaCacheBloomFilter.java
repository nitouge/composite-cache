package io.github.nitouge.cache.core.support.penetration;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Guava {@link BloomFilter} 的<b>本地</b>布隆过滤器实现（默认实现）。
 *
 * <p>特点：每节点一份、微秒级判断、<b>不依赖 Redis</b>（L2 降级时仍可用）。按 cacheName 维护独立过滤器；
 * key 以 {@code key.toString()} 入位，因此自定义 DTO key 需保证<b>稳定的 toString</b>（与查询时一致）。
 *
 * <p>线程安全：Guava {@code BloomFilter} 的 {@code put}/{@code mightContain} 本身线程安全；
 * 注册用 {@link ConcurrentHashMap#computeIfAbsent} 保证幂等。
 *
 * <p>注意：本地过滤器在应用重启后为空，需重新预热；多节点各自维护，{@link #put} 仅写本节点
 * （新建数据建议各节点都能通过自身加载真实值时自动 {@code put} 收敛，或重启时统一预热）。
 *
 */
@Slf4j
public class GuavaCacheBloomFilter implements CacheBloomFilter {

    private final Map<String, BloomFilter<CharSequence>> filters = new ConcurrentHashMap<>();

    private final long defaultExpectedInsertions;

    private final double defaultFpp;

    public GuavaCacheBloomFilter(long defaultExpectedInsertions, double defaultFpp) {
        this.defaultExpectedInsertions = defaultExpectedInsertions > 0 ? defaultExpectedInsertions : 1_000_000L;
        this.defaultFpp = (defaultFpp > 0 && defaultFpp < 1) ? defaultFpp : 0.01;
    }

    @Override
    public boolean isRegistered(String cacheName) {
        return filters.containsKey(cacheName);
    }

    @Override
    public boolean mightContain(String cacheName, Object key) {
        BloomFilter<CharSequence> filter = filters.get(cacheName);
        if (filter == null || key == null) {
            // 未注册 / key 为空：放行，绝不拦截（避免误伤）
            return true;
        }
        return filter.mightContain(key.toString());
    }

    @Override
    public void put(String cacheName, Object key) {
        if (key == null) {
            return;
        }
        BloomFilter<CharSequence> filter = filters.get(cacheName);
        if (filter != null) {
            filter.put(key.toString());
        }
    }

    /**
     * 为指定 cacheName 注册布隆过滤器（基于 {@link ConcurrentHashMap#computeIfAbsent}）。
     *
     * <p><b>幂等</b>：同一 cacheName 二次 register 会沿用首次创建时的容量/fpp，本次传入的新参数被忽略；
     * 如需不同容量/误判率，必须改用不同的 cacheName。{@code expectedInsertions <= 0} 或 {@code fpp}
     * 不在 (0,1) 区间时回退为构造器中的默认值。
     */
    @Override
    public void register(String cacheName, long expectedInsertions, double fpp) {
        long expected = expectedInsertions > 0 ? expectedInsertions : defaultExpectedInsertions;
        double useFpp = (fpp > 0 && fpp < 1) ? fpp : defaultFpp;
        filters.computeIfAbsent(cacheName, name -> {
            log.info("[BloomFilter] register, cacheName={}, expectedInsertions={}, fpp={}", name, expected, useFpp);
            return BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), expected, useFpp);
        });
    }
}
