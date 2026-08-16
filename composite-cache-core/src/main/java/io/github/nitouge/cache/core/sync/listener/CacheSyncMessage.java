package io.github.nitouge.cache.core.sync.listener;

import io.github.nitouge.cache.core.consts.CacheConsts;
import io.github.nitouge.cache.core.consts.enums.CacheSyncTypeEnum;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.slf4j.MDC;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * 缓存同步消息
 *
 */
@Getter
@Setter
@Accessors(chain = true)
public class CacheSyncMessage implements Serializable {

    private static final long serialVersionUID = -1L;

    private String msgSrc;// 缓存同步消息来源

    private String cacheType;// 缓存类型

    private String cacheName;// 缓存名称

    private CacheSyncTypeEnum cacheSyncType;// 操作类型 refresh/clear

    private Object key;// 缓存key

    private Map<String, String> mdcContextMap;

    private String msgDesc;// 描述 用于标记发起消息的触发方法，便于排查问题
    
    // ==================== 增强字段（版本号+重试机制） ====================
    
    /**
     * 版本号（时间戳）
     * <p>用于防止消息乱序，接收端会检查版本号，忽略旧消息
     */
    private long version;
    
    /**
     * 实例ID
     * <p>用于识别消息来源，避免处理自己发送的消息
     */
    private String instanceId;
    
    /**
     * 消息ID（唯一标识）
     * <p>用于ACK确认和去重
     */
    private String messageId;
    
    /**
     * 重试次数
     * <p>记录消息已重试的次数
     */
    private int retryCount;
    
    /**
     * 创建时间
     * <p>用于判断消息是否过期
     */
    private long createTime;

    public CacheSyncMessage() {
        this.mdcContextMap = MDC.getCopyOfContextMap();
    }

    public CacheSyncMessage(String msgSrc, String cacheType, String cacheName, CacheSyncTypeEnum cacheSyncType, Object key, String msgDesc) {
        this.msgSrc = msgSrc;
        this.cacheType = cacheType;
        this.cacheName = cacheName;
        this.cacheSyncType = cacheSyncType;
        this.key = key;
        this.mdcContextMap = MDC.getCopyOfContextMap();
        this.msgDesc = msgDesc;
    }

    public Map<String, String> getMdcContextMap() {
        if (mdcContextMap == null || mdcContextMap.isEmpty()) {
            return mdcContextMap;
        }
        // 返回副本并改写 trace 前缀（便于区分操作本身日志与消息通知触发的日志）；
        // 不原地改写原 map——否则多次调用 getMdcContextMap() 会反复叠加前缀（如 cache_msg:cache_msg:trace）。
        Map<String, String> result = new HashMap<>(mdcContextMap);
        String sid = result.get(CacheConsts.SID);
        if (sid != null && !sid.isEmpty()) {
            result.put(CacheConsts.SID, this.buildNewTraceId(sid));
        }
        String trace_id = result.get(CacheConsts.TRACE_ID);
        if (trace_id != null && !trace_id.isEmpty()) {
            result.put(CacheConsts.TRACE_ID, this.buildNewTraceId(trace_id));
        }
        return result;
    }

    private String buildNewTraceId(String trace_id) {
        /*
         sb.append(CacheConsts.SPLIT);
         sb.append(this.getInstanceId());
        */
        return CacheConsts.PREFIX_CACHE_MSG + CacheConsts.SPLIT_SINGLE + trace_id;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() +
                " [" +
                "Hash = " + hashCode() +
                ", msgSrc=" + msgSrc +
                ", cacheType=" + cacheType +
                ", cacheName=" + cacheName +
                ", cacheSyncType=" + cacheSyncType +
                ", key=" + key +
                ", msgDesc=" + msgDesc +
                ", mdcContextMap=" + mdcContextMap +
                "]";
    }

}
