package io.github.nitouge.cache.annotation.key;

/**
 * 缓存 Key 生成器工厂类
 *
 * <p>提供统一的 Key 生成器创建方法。
 *
 * <h3>支持的生成器</h3>
 * <ul>
 *   <li>{@link CacheKeyGenerator} - 默认生成器</li>
 *   <li>{@link CustomCacheKeyGenerator} - 自定义生成器</li>
 *   <li>{@link SmartCacheKeyGenerator} - 智能生成器</li>
 * </ul>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 获取默认生成器
 * CacheKeyGenerator defaultGenerator = CacheKeyGeneratorFactory.getDefault();
 *
 * // 获取自定义生成器
 * CacheKeyGenerator customGenerator = CacheKeyGeneratorFactory.getCustom();
 *
 * // 获取智能生成器
 * CacheKeyGenerator smartGenerator = CacheKeyGeneratorFactory.getSmart();
 *
 * // 获取智能生成器（自定义阈值）
 * CacheKeyGenerator smartGenerator2 = CacheKeyGeneratorFactory.getSmart(300, 10);
 * }</pre>
 *
 */
public class CacheKeyGeneratorFactory {

    /**
     * 默认生成器实例（单例）
     */
    private static final CacheKeyGenerator DEFAULT_GENERATOR = new CacheKeyGenerator() {};

    /**
     * 自定义生成器实例（单例）
     */
    private static final CacheKeyGenerator CUSTOM_GENERATOR = new CustomCacheKeyGenerator();

    /**
     * 智能生成器实例（单例）
     */
    private static final CacheKeyGenerator SMART_GENERATOR = new SmartCacheKeyGenerator();

    private CacheKeyGeneratorFactory() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * 获取默认生成器
     *
     * <p>生成规则：
     * <ul>
     *   <li>无参数 → {@link DefaultKey#EMPTY}</li>
     *   <li>单参数（非数组） → 直接返回参数</li>
     *   <li>多参数或数组 → {@link DefaultKey}</li>
     * </ul>
     *
     * @return 默认生成器
     */
    public static CacheKeyGenerator getDefault() {
        return DEFAULT_GENERATOR;
    }

    /**
     * 获取自定义生成器
     *
     * <p>生成规则：{@code ClassName:methodName:param1:param2:...}
     *
     * @return 自定义生成器
     */
    public static CacheKeyGenerator getCustom() {
        return CUSTOM_GENERATOR;
    }

    /**
     * 获取智能生成器（使用默认配置）
     *
     * <p>默认配置：
     * <ul>
     *   <li>Key 长度阈值：200 字符</li>
     *   <li>最大嵌套深度：5 层</li>
     * </ul>
     *
     * @return 智能生成器
     */
    public static CacheKeyGenerator getSmart() {
        return SMART_GENERATOR;
    }

    /**
     * 获取智能生成器（自定义配置）
     *
     * @param keyLengthThreshold Key 长度阈值，超过此长度使用 MD5
     * @param maxNestingDepth 最大嵌套深度
     * @return 智能生成器
     */
    public static CacheKeyGenerator getSmart(int keyLengthThreshold, int maxNestingDepth) {
        return new SmartCacheKeyGenerator(keyLengthThreshold, maxNestingDepth);
    }
}
