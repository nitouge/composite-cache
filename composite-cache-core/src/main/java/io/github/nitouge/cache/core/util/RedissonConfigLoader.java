package io.github.nitouge.cache.core.util;

import io.github.nitouge.cache.core.exception.CacheConfigException;
import org.redisson.config.Config;

import java.io.IOException;
import java.io.InputStream;

/**
 * Redisson配置文件加载工具类
 *
 */
public final class RedissonConfigLoader {

    private RedissonConfigLoader() {
    }

    /**
     * 从classpath加载Redisson YAML配置文件
     *
     * @param yamlConfigPath YAML配置文件路径（classpath下）
     * @return Redisson Config对象
     * @throws CacheConfigException 配置文件未找到或解析失败时抛出
     */
    public static Config loadFromYaml(String yamlConfigPath) {
        if (yamlConfigPath == null || yamlConfigPath.trim().isEmpty()) {
            return null;
        }
        try {
            // 此方式可获取到springboot打包以后jar包内的资源文件
            InputStream is = Thread.currentThread().getContextClassLoader().getResourceAsStream(yamlConfigPath);
            if (null == is) {
                throw new CacheConfigException(
                        "redissonYamlConfig",
                        yamlConfigPath,
                        "Redisson yaml config file not found");
            }
            return Config.fromYAML(is);
        } catch (IOException e) {
            throw new CacheConfigException(
                    "Failed to parse Redisson yaml config file: " + yamlConfigPath, e);
        }
    }
}
