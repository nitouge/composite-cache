package io.github.nitouge.cache.core.provider;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheProvider;
import io.github.nitouge.cache.core.api.CacheSyncPolicy;
import io.github.nitouge.cache.core.api.L1Cache;
import io.github.nitouge.cache.core.api.L2Cache;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import io.github.nitouge.cache.core.consts.enums.CacheMsgTypeEnum;
import io.github.nitouge.cache.core.consts.enums.CacheTypeEnum;
import io.github.nitouge.cache.core.impl.CompositeCache;
import io.github.nitouge.cache.core.provider.factory.CacheProviderFactory;
import io.github.nitouge.cache.core.sync.factory.CacheSyncPolicyFactory;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;

/**
 * 缓存 Provider 持有者：依据 {@link CacheConfig#getCacheMode() cacheMode} 一次性装配并持有
 * L1 / L2 {@link CacheProvider} 及同步策略，作为后续按 cacheName 创建缓存实例的工厂。
 *
 * <p>L1 模式只装配 L1 provider，L2 模式只装配 L2 provider，COMPOSITE（默认分支）两者皆装配。
 * 同步策略仅注入 L1 provider（跨节点失效广播的发送方）。
 *
 */
@Getter
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CacheProviderHolder {

    private CacheProvider<?, ?> l1CacheProvider;

    private CacheProvider<?, ?> l2CacheProvider;

    private CacheConfig cacheConfig;

    /**
     * 静态构建持有者：按 cacheMode 装配对应层级的 provider，并为 L1 注入同步策略、为 L2 注入 Redisson 客户端。
     *
     * @param cacheConfig       全局缓存配置，不可为 null
     * @param actualCacheClient L2（Redis）实际客户端；L1 模式下可不参与装配
     * @return 装配完成的持有者
     * @throws IllegalArgumentException cacheConfig 为 null 时
     */
    public static CacheProviderHolder init(CacheConfig cacheConfig, RedissonClient actualCacheClient) {
        if (cacheConfig == null) {
            throw new IllegalArgumentException("缓存配置不能为null");
        }

        CacheProviderHolder holder = new CacheProviderHolder();
        holder.cacheConfig = cacheConfig;

        CacheModeEnum cacheModeEnum = cacheConfig.getCacheMode();
        CacheSyncPolicy<?> cacheSyncPolicy = initSyncPolicy(cacheConfig);

        logInitInfo(cacheConfig, cacheModeEnum);

        switch (cacheModeEnum) {
            case L1:
                holder.l1CacheProvider = initL1Provider(cacheConfig, cacheSyncPolicy);
                break;
            case L2:
                holder.l2CacheProvider = initL2Provider(cacheConfig, actualCacheClient);
                break;
            default:
                holder.l1CacheProvider = initL1Provider(cacheConfig, cacheSyncPolicy);
                holder.l2CacheProvider = initL2Provider(cacheConfig, actualCacheClient);
                break;
        }

        return holder;
    }

    private static CacheSyncPolicy<?> initSyncPolicy(CacheConfig cacheConfig) {
        // 传入完整同步配置：REDIS 默认走可靠版增强策略，可通过 cacheSyncPolicy.enhanced=false 回退基础版
        return CacheSyncPolicyFactory.create(cacheConfig.getCacheSyncPolicy());
    }

    /** 创建 L1 provider 并注入同步策略（L1 为跨节点失效广播的发送方）。 */
    private static CacheProvider<?, ?> initL1Provider(CacheConfig cacheConfig, CacheSyncPolicy<?> cacheSyncPolicy) {
        CacheTypeEnum l1CacheType = cacheConfig.getComposite().getL1CacheType();
        CacheProvider<?, ?> provider = CacheProviderFactory.getCacheProvider(l1CacheType, cacheConfig);
        provider.setCacheSyncPolicy(cacheSyncPolicy);
        log.debug("L1 cache provider initialized: type={}", l1CacheType);
        return provider;
    }

    /** 创建 L2 provider 并注入 Redisson 客户端。 */
    @SuppressWarnings("unchecked")
    private static CacheProvider<?, ?> initL2Provider(CacheConfig cacheConfig, RedissonClient actualCacheClient) {
        CacheTypeEnum l2CacheType = cacheConfig.getComposite().getL2CacheType();
        CacheProvider<?, RedissonClient> provider =
                (CacheProvider<?, RedissonClient>) CacheProviderFactory.getCacheProvider(l2CacheType, cacheConfig);
        provider.setActualCacheClient(actualCacheClient);
        log.debug("L2 cache provider initialized: type={}", l2CacheType);
        return provider;
    }

    private static void logInitInfo(CacheConfig cacheConfig, CacheModeEnum cacheModeEnum) {
        CacheTypeEnum l1Type = cacheConfig.getComposite().getL1CacheType();
        CacheTypeEnum l2Type = cacheConfig.getComposite().getL2CacheType();
        CacheMsgTypeEnum msgType = cacheConfig.getCacheSyncPolicy().getMsgType();

        log.info("CacheProviderHolder initialized: mode={}, L1={}, L2={}, sync={}",
                cacheModeEnum, l1Type, l2Type, msgType);
    }

    /**
     * 按缓存模式创建缓存实例：优先取 {@link CacheSetting} 上指定的模式，未指定则回退全局 cacheMode。
     *
     * <p>当注解配置的 cacheMode 与全局配置文件的 cache-mode 不一致时：
     * <ul>
     *   <li>注解要求 L1，但全局只初始化了 L2 -> 降级为 L2 only（L1 不可用）</li>
     *   <li>注解要求 L2，但全局只初始化了 L1 -> 降级为 L1 only（L2 不可用）</li>
     *   <li>注解要求 L1_L2，但全局只初始化了 L1 -> 降级为 L1 only</li>
     *   <li>注解要求 L1_L2，但全局只初始化了 L2 -> 降级为 L2 only</li>
     * </ul>
     *
     * @param cacheName    缓存名，不可为空
     * @param cacheSetting 该缓存的独立设置，不可为 null
     * @return 对应模式的缓存（L1 / L2 / 组合缓存）
     * @throws IllegalArgumentException cacheName 为空或 cacheSetting 为 null 时
     */
    public Cache getCache(String cacheName, CacheSetting cacheSetting) {
        if (cacheName == null || cacheName.isEmpty()) {
            throw new IllegalArgumentException("缓存名称不能为null或空");
        }
        if (cacheSetting == null) {
            throw new IllegalArgumentException("缓存配置不能为null");
        }

        CacheModeEnum requestedMode = determineCacheMode(cacheSetting);
        CacheModeEnum effectiveMode = validateAndAdjustCacheMode(cacheName, requestedMode);

        switch (effectiveMode) {
            case L1:
                return getL1Cache(cacheName, cacheSetting);
            case L2:
                return getL2Cache(cacheName, cacheSetting);
            default:
                return getCompositeCache(cacheName, cacheSetting);
        }
    }

    private CacheModeEnum determineCacheMode(CacheSetting cacheSetting) {
        CacheModeEnum cacheModeEnum = cacheSetting.getCacheModeEnum();
        if (cacheModeEnum != null) {
            return cacheModeEnum;
        }
        return cacheConfig.getCacheMode();
    }

    /**
     * 根据可用的缓存提供者校验并调整缓存模式。
     *
     * @param cacheName     缓存名称，用于日志记录
     * @param requestedMode 注解或配置中请求的缓存模式
     * @return 经过校验和调整后的实际生效模式
     */
    private CacheModeEnum validateAndAdjustCacheMode(String cacheName, CacheModeEnum requestedMode) {
        boolean hasL1 = l1CacheProvider != null;
        boolean hasL2 = l2CacheProvider != null;

        // 如果请求的模式与可用提供者匹配，则无需调整
        if (requestedMode == CacheModeEnum.L1 && hasL1) {
            return CacheModeEnum.L1;
        }
        if (requestedMode == CacheModeEnum.L2 && hasL2) {
            return CacheModeEnum.L2;
        }
        if (requestedMode == CacheModeEnum.L1_L2 && hasL1 && hasL2) {
            return CacheModeEnum.L1_L2;
        }

        // 需要调整 - 记录警告日志并进行降级处理
        if (requestedMode == CacheModeEnum.L1 && !hasL1) {
            log.warn("Cache mode conflict: cacheName={}, requested=L1, but L1 provider not initialized (global config={}). Degrading to L2.",
                    cacheName, cacheConfig.getCacheMode());
            return CacheModeEnum.L2;
        }
        if (requestedMode == CacheModeEnum.L2 && !hasL2) {
            log.warn("Cache mode conflict: cacheName={}, requested=L2, but L2 provider not initialized (global config={}). Degrading to L1.",
                    cacheName, cacheConfig.getCacheMode());
            return CacheModeEnum.L1;
        }
        if (requestedMode == CacheModeEnum.L1_L2) {
            if (!hasL1 && hasL2) {
                log.warn("Cache mode conflict: cacheName={}, requested=L1_L2, but L1 provider not initialized (global config={}). Degrading to L2.",
                        cacheName, cacheConfig.getCacheMode());
                return CacheModeEnum.L2;
            }
            if (hasL1 && !hasL2) {
                log.warn("Cache mode conflict: cacheName={}, requested=L1_L2, but L2 provider not initialized (global config={}). Degrading to L1.",
                        cacheName, cacheConfig.getCacheMode());
                return CacheModeEnum.L1;
            }
        }

        // 正常情况下不应执行到此处
        throw new IllegalStateException(String.format(
                "Invalid cache mode configuration: cacheName=%s, requested=%s, hasL1=%s, hasL2=%s",
                cacheName, requestedMode, hasL1, hasL2));
    }

    /**
     * 创建并返回独立的 L1 缓存实例。
     */
    public L1Cache getL1Cache(String cacheName, CacheSetting cacheSetting) {
        return (L1Cache) l1CacheProvider.build(cacheName, cacheSetting);
    }

    /**
     * 创建并返回 L2 缓存实例。
     *
     * <p>L2 provider 缺失（如未配置 Redis）时返回 {@code null}（仅告警，不抛异常），供上层降级判断；
     * provider 存在但构建失败则包装为 {@link RuntimeException} 抛出。
     */
    public L2Cache getL2Cache(String cacheName, CacheSetting cacheSetting) {
        if (l2CacheProvider == null) {
            log.warn("L2CacheProvider is null, cannot create L2 cache: cacheName={}", cacheName);
            return null;
        }

        try {
            L2Cache l2Cache = (L2Cache) l2CacheProvider.build(cacheName, cacheSetting);
            log.debug("L2 cache created: cacheName={}, type={}",
                    cacheName, l2Cache != null ? l2Cache.getClass().getSimpleName() : "null");
            return l2Cache;
        } catch (Exception e) {
            log.error("Failed to create L2 cache: cacheName={}", cacheName, e);
            throw new RuntimeException("Failed to create L2 cache: " + cacheName, e);
        }
    }

    /**
     * 创建组合（L1 + L2）缓存实例。
     *
     * <p>当 L2 不可用（{@link #getL2Cache} 返回 null）时<b>降级为纯 L1</b>，直接返回 L1 缓存，
     * 避免以 null 的 L2 构造组合缓存后在读写路径上 NPE。
     */
    public Cache getCompositeCache(String cacheName, CacheSetting cacheSetting) {
        try {
            L1Cache l1Cache = getL1Cache(cacheName, cacheSetting);
            L2Cache l2Cache = getL2Cache(cacheName, cacheSetting);

            // L2 不可用（无 RedissonClient / L2 provider 缺失）时安全降级为纯 L1，
            // 避免用 null 的 l2Cache 构造 CompositeCache 后在读写路径上 NPE。
            // （Redisson 为必需依赖；此分支对应"未配置/连不上 Redis"的退化场景。）
            if (l2Cache == null) {
                log.warn("L2 cache unavailable (no RedissonClient?), degrade to L1-only for cacheName={}", cacheName);
                return l1Cache;
            }

            log.debug("CompositeCache created: cacheName={}, L1={}, L2={}",
                    cacheName,
                    l1Cache != null ? l1Cache.getClass().getSimpleName() : "null",
                    l2Cache.getClass().getSimpleName());

            return new CompositeCache(cacheName, cacheConfig, l1Cache, l2Cache);
        } catch (Exception e) {
            log.error("Failed to create CompositeCache: cacheName={}", cacheName, e);
            throw new RuntimeException("Failed to create CompositeCache: " + cacheName, e);
        }
    }
}
