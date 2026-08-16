package io.github.nitouge.cache.core.consts.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 缓存同步消息所使用的中间件类型。
 *
 * <p>用于在多节点间广播缓存失效/刷新消息。目前仅 {@link #REDIS}（默认可靠版）与 {@link #KAFKA}
 * 已实现，{@link #ROCKETMQ}、{@link #RABBITMQ} 暂未支持，配置后会在创建同步策略时抛出异常。
 *
 */
@Getter
@AllArgsConstructor
@ToString
public enum CacheMsgTypeEnum {

    /**
     * Kafka消息
     */
    KAFKA,
    
    /**
     * Redis消息
     */
    REDIS,
    
    /**
     * RocketMQ消息
     *
     * @deprecated 暂未实现：配置后会在创建同步策略时抛出 {@code UnsupportedOperationException}。
     * 目前仅支持 {@link #REDIS}（默认可靠版）与 {@link #KAFKA}。
     */
    @Deprecated
    ROCKETMQ,

    /**
     * RabbitMQ消息
     *
     * @deprecated 暂未实现：配置后会在创建同步策略时抛出 {@code UnsupportedOperationException}。
     * 目前仅支持 {@link #REDIS}（默认可靠版）与 {@link #KAFKA}。
     */
    @Deprecated
    RABBITMQ,


    ;

    /**
     * 根据消息类型名称获取枚举
     * 
     * @param msgType 消息类型名称
     * @return 消息类型枚举
     * @throws IllegalArgumentException 如果找不到对应的消息类型
     */
    public static CacheMsgTypeEnum getCacheMsgTypeEnum(String msgType) {
        for (CacheMsgTypeEnum modeEnum : CacheMsgTypeEnum.values()) {
            if (modeEnum.name().equals(msgType)) {
                return modeEnum;
            }
        }
        throw new IllegalArgumentException("非法参数，无法找到参数【" + msgType + "】对应的缓存同步消息类型");
    }
    
    /**
     * 获取所有消息类型
     * 
     * @return 所有消息类型的数组
     */
    public static CacheMsgTypeEnum[] getAllTypes() {
        return CacheMsgTypeEnum.values();
    }

}
