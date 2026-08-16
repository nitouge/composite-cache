package io.github.nitouge.cache.core.exception;

import lombok.Getter;

/**
 * 缓存配置异常
 * 
 * <p>当缓存配置不正确或缺失时抛出此异常。
 * 
 * <h3>常见场景</h3>
 * <ul>
 *   <li>缓存配置参数缺失</li>
 *   <li>缓存配置参数不合法</li>
 *   <li>缓存提供者未正确配置</li>
 *   <li>缓存同步策略配置错误</li>
 * </ul>
 * 
 */
@Getter
public class CacheConfigException extends CompositeCacheException {

    private static final long serialVersionUID = 1L;

    /**
     * 配置项名称
     */
    private final String configKey;

    /**
     * 配置项值
     */
    private final Object configValue;

    public CacheConfigException(String message) {
        super(message);
        this.configKey = null;
        this.configValue = null;
    }

    public CacheConfigException(String message, Throwable cause) {
        super(message, cause);
        this.configKey = null;
        this.configValue = null;
    }

    public CacheConfigException(String configKey, Object configValue, String message) {
        super(buildMessage(configKey, configValue, message));
        this.configKey = configKey;
        this.configValue = configValue;
    }

    /**
     * 构建异常消息
     */
    private static String buildMessage(String configKey, Object configValue, String message) {
        return String.format("Invalid cache config [%s=%s]: %s", configKey, configValue, message);
    }

}
