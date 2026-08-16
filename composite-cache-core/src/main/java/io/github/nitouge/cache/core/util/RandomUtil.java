package io.github.nitouge.cache.core.util;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机工具类。
 *
 */
public final class RandomUtil {

    private RandomUtil() {
    }

    /**
     * 基于 UUID 生成随机字符串（用于 trace id 等）。
     */
    public static String getUUID() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * 给基础过期时间叠加随机抖动，用于缓解缓存雪崩（大量 key 同一时刻集体过期）。
     *
     * <p>返回值 = {@code base + random[0, base*ratio]}，落在 {@code [base, base*(1+ratio)]} 区间内，
     * 使不同 key 的过期时间被打散。
     *
     * <p>当 {@code base<=0}（永久/不过期）或 {@code ratio<=0}（关闭抖动）时，原样返回 {@code base}，
     * 保证"不设置 TTL"的语义不被破坏。
     *
     * @param base  基础过期时间（任意时间单位，调用方自行保证单位一致）
     * @param ratio 抖动比例（如 0.1 表示最多上浮 10%）
     * @return 叠加抖动后的过期时间
     */
    public static long jitter(long base, double ratio) {
        if (base <= 0 || ratio <= 0) {
            return base;
        }
        long delta = (long) (base * ratio);
        if (delta <= 0) {
            return base;
        }
        return base + ThreadLocalRandom.current().nextLong(delta + 1);
    }
}
