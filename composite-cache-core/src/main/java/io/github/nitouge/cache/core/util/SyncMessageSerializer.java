package io.github.nitouge.cache.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 内部 JSON 工具：仅用于缓存同步消息（{@code CacheSyncMessage}）的序列化/反序列化，当前由 Kafka 同步策略使用。
 *
 * <p><b>不参与 L2 缓存值的序列化</b>（L2 由 Redisson 自身的 codec 负责）。
 * <p>约定：{@link #toJson(Object)} 对 {@code null} 返回空串；{@link #toObject(String, Class)} 对空串/空白返回 {@code null}。
 *
 */
public class SyncMessageSerializer {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        // 自动装载 classpath 上的 Jackson 模块（如 jackson-datatype-jsr310，Spring Boot 的 jackson starter 通常已带）。
        // 使含 LocalDateTime 等 JSR310 类型的同步消息 key 也能正确序列化，避免序列化异常导致失效消息被静默丢弃、
        // 进而其它节点 L1 不被清理而读到陈旧值。classpath 上无相应模块时本调用为无副作用的空操作。
        objectMapper.findAndRegisterModules();
    }

    /**
     * 将对象转换为 JSON
     */
    public static String toJson(Object value) {
        if (null == value) {
            return "";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("convert to json error", e);
        }
    }

    /**
     * 将 JSON 转换为对象
     */
    public static <T> T toObject(String json, Class<T> clazz) {
        if (null == json || json.trim().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("convert to " + clazz.getName() + " error", e);
        }
    }
}
