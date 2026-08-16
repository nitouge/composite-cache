package io.github.nitouge.cache.prop;

import io.github.nitouge.cache.core.config.CacheConfig;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Composite Cache配置属性
 *
 * <p>用于Spring Boot自动配置。
 *
 * <h3>配置示例</h3>
 * <pre>
 * composite-cache:
 *   enabled: true
 *   key-generator-strategy: CUSTOM  # DEFAULT, CUSTOM, SMART
 *   annotation:
 *     metrics:
 *       enabled: true
 *   config:
 *     allow-null-values: true
 *     null-value-expire-time-seconds: 60
 * </pre>
 *
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "composite-cache")
public class CompositeCacheProperties {

    /**
     * 是否启用缓存
     */
    private Boolean enabled = false;

    /**
     * Key生成器策略
     *
     * <p>可选值：
     * <ul>
     *   <li>DEFAULT - 默认策略，单参数直接返回，多参数封装为DefaultKey</li>
     *   <li>CUSTOM - 自定义策略，包含类名和方法名（推荐）</li>
     *   <li>SMART - 智能策略，自动处理大对象（使用MD5）</li>
     * </ul>
     */
    private String keyGeneratorStrategy = "CUSTOM";

    /**
     * 是否启用缓存监控
     */
    private Metrics metrics;

    /**
     * 核心配置
     */
    private CacheConfig config;


    @Getter
    @Setter
    public static class Metrics {

        /**
         * 是否启用
         */
        private Boolean enabled = false;

        /**
         * 上报间隙，默认15s
         */
        private Integer reportInterval = 15;

    }
}
