package io.github.nitouge.cache.core.config.setting;

import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.util.concurrent.TimeUnit;

/**
 * 单个缓存的运行期设置：缓存模式（{@link CacheModeEnum}：L1 / L2 / L1_L2）及对应的 L1、L2 子设置。
 *
 * <p>可序列化，便于随缓存元数据在进程间（如经 Redis 广播刷新配置）传递。{@link #cacheModeEnum} 决定
 * 实际启用哪一级：仅用 L1 时 {@link #l2CacheSetting} 可为 {@code null}，反之亦然。
 *
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class CacheSetting implements Serializable {

    private static final long serialVersionUID = 1L;

    private CacheModeEnum cacheModeEnum;

    private L1CacheSetting l1CacheSetting;

    private L2CacheSetting l2CacheSetting;

    /**
     * 构建一份默认的多级缓存配置（L1: 1000 条/2 分钟；L2: 5 分钟）。
     *
     * <p>供编程式 API（如缓存尚未由注解创建时）按需动态创建缓存使用，避免各处重复硬编码默认值。
     */
    public static CacheSetting defaultSetting() {
        L1CacheSetting l1 = new L1CacheSetting();
        l1.setInitialCapacity(100);
        l1.setMaximumSize(1000);
        l1.setExpireTime(60L);
        l1.setExpireTimeUnit(TimeUnit.SECONDS);

        L2CacheSetting l2 = new L2CacheSetting();
        l2.setExpireTime(300L);
        l2.setExpireTimeUnit(TimeUnit.SECONDS);

        return new CacheSetting(CacheModeEnum.L1_L2, l1, l2);
    }
}
