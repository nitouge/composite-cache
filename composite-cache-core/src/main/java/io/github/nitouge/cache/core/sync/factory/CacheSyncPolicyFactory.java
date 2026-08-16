package io.github.nitouge.cache.core.sync.factory;

import io.github.nitouge.cache.core.api.CacheSyncPolicy;
import io.github.nitouge.cache.core.config.CacheConfig;
import io.github.nitouge.cache.core.consts.enums.CacheMsgTypeEnum;
import io.github.nitouge.cache.core.sync.EnhancedRedisCacheSyncPolicy;
import io.github.nitouge.cache.core.sync.KafkaCacheSyncPolicy;
import io.github.nitouge.cache.core.sync.RedisCacheSyncPolicy;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 缓存同步策略工厂：依据同步配置（msgType / enhanced 等）创建跨节点缓存失效广播的 {@link CacheSyncPolicy}。
 *
 * <p>REDIS 默认走可靠版增强策略，KAFKA 走 Kafka 策略；未实现的类型（ROCKETMQ / RABBITMQ）fail-fast 抛出，
 * 避免静默返回 null 导致跨节点不同步。
 *
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CacheSyncPolicyFactory {

    /**
     * 依据同步配置创建缓存同步策略。
     *
     * <p>REDIS 类型默认使用<b>可靠版</b> {@link EnhancedRedisCacheSyncPolicy}
     * （消息版本门控防乱序 + messageId 去重 + 失败补偿，可选 ACK 确认）；
     * 可通过 {@code cacheSyncPolicy.enhanced=false} 回退到基础版 {@link RedisCacheSyncPolicy}。
     *
     * @param syncConfig 同步策略配置（含 msgType、enhanced、enableAck、maxRetries 等）
     * @return 同步策略；msgType 为空或不支持时返回 null
     */
    public static CacheSyncPolicy<?> create(CacheConfig.CacheSyncPolicyConfig syncConfig) {
        if (syncConfig == null || syncConfig.getMsgType() == null) {
            return null;
        }
        switch (syncConfig.getMsgType()) {
            case REDIS:
                if (syncConfig.isEnhanced()) {
                    EnhancedRedisCacheSyncPolicy policy = new EnhancedRedisCacheSyncPolicy();
                    policy.setEnableAck(syncConfig.isEnableAck());
                    policy.setMaxRetries(syncConfig.getMaxRetries());
                    return policy;
                }
                return new RedisCacheSyncPolicy();
            case KAFKA:
                return new KafkaCacheSyncPolicy();
            default:
                // 快速失败：未实现的类型（ROCKETMQ/RABBITMQ）不再静默返回 null（会导致跨节点静默不同步），
                // 显式抛出以暴露配置错误。
                throw new UnsupportedOperationException("Unsupported cache sync msgType: " + syncConfig.getMsgType()
                        + ". Supported: REDIS, KAFKA. (ROCKETMQ/RABBITMQ are not implemented)");
        }
    }

    /**
     * @deprecated 请改用 {@link #create(CacheConfig.CacheSyncPolicyConfig)} 以支持可靠同步策略选择；
     * 本方法仅创建基础版策略，保留用于向后兼容。
     */
    @Deprecated
    public static CacheSyncPolicy<?> create(CacheMsgTypeEnum msgType) {
        if (msgType == null) {
            return null;
        }
        switch (msgType) {
            case REDIS:
                return new RedisCacheSyncPolicy();
            case KAFKA:
                return new KafkaCacheSyncPolicy();
            default:
                throw new UnsupportedOperationException("Unsupported cache sync msgType: " + msgType
                        + ". Supported: REDIS, KAFKA. (ROCKETMQ/RABBITMQ are not implemented)");
        }
    }
}
