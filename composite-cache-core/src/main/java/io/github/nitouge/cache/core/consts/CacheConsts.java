package io.github.nitouge.cache.core.consts;

/**
 * 缓存相关的全局字符串常量（key 前缀、分隔符、链路跟踪字段名等）。
 *
 */
public class CacheConsts {

    /**
     * 缓存实例 ID 的前缀，用于拼接全局唯一的实例标识。
     */
    public static final String CACHE_INSTANCE_PREFIX = "cache_instance_";

    /**
     * 分隔符
     */
    public static final String SPLIT_SINGLE = ":";

    /**
     * 多级/物理 key 段分隔符 {@code ":::"}，用于区分逻辑层级，区别于单冒号 {@link #SPLIT_SINGLE}。
     */
    public static final String SPLIT_MULTI = ":::";

    /**
     * 通配符 *
     */
    public static final String ASTERISK = "*";

    /**
     * 逗号，用于多值拼接/拆分。
     */
    public static final String COMMA = ",";


    // 链路跟踪的字段名
    /**
     * MDC 链路跟踪字段：会话/调用链 ID。
     */
    public static final String SID = "sid";

    /**
     * MDC 链路跟踪字段：trace ID。
     */
    public static final String TRACE_ID = "trace_id";

    /**
     * 缓存消息（如刷新广播）的 trace_id 前缀。
     */
    public static final String PREFIX_CACHE_MSG = "CACHE_MSG";

    /**
     * 清理NullValue的Task的trace_id的前缀
     */
    public static final String PREFIX_CLEAR_NULL_VALUE = "CLEAR_NULL_VALUE";
}
