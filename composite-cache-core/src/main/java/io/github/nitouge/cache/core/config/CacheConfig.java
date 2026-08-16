package io.github.nitouge.cache.core.config;

import io.github.nitouge.cache.core.consts.CacheConsts;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import io.github.nitouge.cache.core.consts.enums.CacheMsgTypeEnum;
import io.github.nitouge.cache.core.consts.enums.CacheTypeEnum;
import io.github.nitouge.cache.core.consts.enums.RedisLoadStrategyEnum;
import io.github.nitouge.cache.core.util.RedissonConfigLoader;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.util.Properties;
import java.util.UUID;


@Getter
@Setter
@Accessors(chain = true)
@ToString
public class CacheConfig {

    /**
     * 缓存实例id，默认值为基于Snowflake的简单ID
     */
    private String instanceId = CacheConsts.CACHE_INSTANCE_PREFIX + UUID.randomUUID().toString().replace("-", "");

    /**
     * 是否存储空值，设置为true时，可防止缓存穿透
     */
    private boolean allowNullValues = true;

    /**
     * NullValue的过期时间，单位秒，默认30秒
     * 用于淘汰NullValue的值
     * 注：当缓存项的过期时间小于该值时，则NullValue不会淘汰
     */
    private long nullValueExpireTimeSeconds = 60L;

    /**
     * NullValue的最大数量，防止出现内存溢出
     * 注：当超出该值时，会在下一次刷新缓存时，淘汰掉NullValue的元素
     */
    private long nullValueMaxSize = 3000L;

    /**
     * NullValue的清理频率(秒)
     */
    private long nullValueClearPeriodSeconds = 10L;

    /**
     * 是否动态根据cacheName创建Cache的实现，默认true
     */
    private boolean dynamic = true;

    /**
     * 缓存模式，默认L1_L2组合缓存
     */
    private CacheModeEnum cacheMode = CacheModeEnum.L1_L2;

    private final CompositeConfig composite = new CompositeConfig();

    private final CaffeineConfig caffeine = new CaffeineConfig();

    private final GuavaConfig guava = new GuavaConfig();

    private final RedisConfig redis = new RedisConfig();

    private final CacheSyncPolicyConfig cacheSyncPolicy = new CacheSyncPolicyConfig();

    private final ConsistencyConfig consistency = new ConsistencyConfig();

    private final PenetrationConfig penetration = new PenetrationConfig();


    public interface Config {
    }

    /**
     * 组合缓存配置
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    @ToString
    public static class CompositeConfig implements Config {
        /**
         * 一级缓存类型
         */
        private CacheTypeEnum l1CacheType = CacheTypeEnum.CAFFEINE;

        /**
         * 二级缓存类型
         */
        private CacheTypeEnum l2CacheType = CacheTypeEnum.REDIS;

    }

    /**
     * L1缓存公共配置基类
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    @ToString
    public abstract static class AbstractL1Config implements Config {

        /**
         * 是否手动缓存
         */
        private boolean manualCache = false;

        /**
         * 是否自动刷新过期缓存（true表示是，false 表示否）
         */
        private boolean autoRefreshExpireCache = false;

        /**
         * 缓存刷新调度线程池的大小
         * 默认为CPU数 * 2
         */
        private Integer refreshThreadPoolSize = Runtime.getRuntime().availableProcessors();

        /**
         * 缓存刷新的频率(秒)
         */
        private Long refreshPeriod = 30L;

        /**
         * 同一个key的发布消息频率(毫秒)
         */
        private Long publishMsgPeriodMilliSeconds = 500L;

    }

    /**
     * Caffeine 缓存特定配置
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    @ToString(callSuper = true)
    public static class CaffeineConfig extends AbstractL1Config {
    }

    /**
     * Guava 缓存特定配置
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    @ToString(callSuper = true)
    public static class GuavaConfig extends AbstractL1Config {
    }

    /**
     * Redis 缓存特定配置
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    @ToString
    public static class RedisConfig implements Config {

        /**
         * 加载数据时，是否加锁
         * @deprecated 请使用 {@link #loadStrategy} 选择回源策略；保留该字段仅为向后兼容
         */
        @Deprecated
        private boolean lock = false;

        /**
         * 加载数据时是调用 tryLock()，还是 lock()
         * 注：
         * tryLock() 只有一个请求执行加载动作，其他并发请求直接返回失败
         * lock() 只有一个请求执行加载动作，其他并发请求会阻塞直到获得锁
         */
        private boolean tryLock = true;

        /**
         * L2 失效后的回源策略：NONE（不加保护）/ LOCK（分布式锁）/ LOGICAL_EXPIRE（逻辑过期）。
         * <p>未显式配置时为 null，运行时通过 {@link #getEffectiveLoadStrategy()} 依据 {@link #lock} 推导，
         * 以保持与旧配置的向后兼容。
         */
        private RedisLoadStrategyEnum loadStrategy;

        /**
         * 逻辑过期模式下，物理 TTL 相对逻辑 TTL 的放大倍数（保证逻辑过期后旧值仍在、可被异步刷新）。
         */
        private int logicalExpirePhysicalTtlFactor = 2;

        /**
         * TTL 随机抖动比例（{@code >=0}），用于缓解<b>缓存雪崩</b>（大量 key 同一时刻集体过期）。
         * <p>实际写入 TTL = {@code base + random[0, base*ratio]}，即在基础 TTL 上最多上浮 {@code ratio}。
         * <p>默认 {@code 0.1}（上浮 10%）。设为 {@code 0} 关闭抖动。
         * 仅对设置了正数 TTL 的缓存项生效；永久缓存项（TTL<=0）不受影响。NullValue 的 TTL 同样参与抖动。
         */
        private double ttlJitterRatio = 0.1;

        /**
         * 是否启用 <b>L2 运行期降级</b>（熔断）：Redis 抖动/不可用时，读自动降级为"仅 L1 + 回源 DB"、
         * 写安全跳过，避免每个请求卡在 Redis 超时上；Redis 恢复后自动闭合。
         * <p><b>默认关闭</b>：开启后会吞掉 L2 的连接类异常（不再向上抛出），属于语义变化，请按需显式开启。
         * 仅对 Redisson 连接类异常（{@code RedisException}，含超时/连接失败）生效；序列化等其它异常照常抛出。
         */
        private boolean degradeEnabled = false;

        /**
         * 降级熔断：连续失败达到该阈值则打开熔断（短路降级）。默认 5。
         */
        private int degradeFailureThreshold = 5;

        /**
         * 降级熔断：打开后的短路持续时间（毫秒），到期进入半开试探。默认 10000。
         */
        private long degradeOpenMillis = 10_000L;

        /**
         * 降级期间回源 DB 的最大并发数（保护 DB），{@code <=0} 表示不限制。
         * <p>注：L1（LoadingCache）已对同一 key 提供单飞保护，本项是对总并发的额外上限。默认 0（不限制）。
         */
        private int degradeMaxConcurrentLoads = 0;

        /**
         * 获取实际生效的回源策略：显式配置优先，否则按 {@code lock} 布尔向后兼容推导。
         */
        public RedisLoadStrategyEnum getEffectiveLoadStrategy() {
            if (loadStrategy != null) {
                return loadStrategy;
            }
            return lock ? RedisLoadStrategyEnum.LOCK : RedisLoadStrategyEnum.NONE;
        }

        /**
         * 是否使用缓存名称作为Redis key的前缀
         */
        private boolean userPrefix = true;

        /**
         * 是否支持批量操作
         */
        private boolean supportBatch = false;

        /**
         * 批量操作的大小
         */
        private int batchSize = 50;

        /**
         * Redisson 的yaml配置文件
         */
        private String redissonYamlConfig;

        /**
         * Redisson Config
         */
        private org.redisson.config.Config redissonConfig;

        /**
         * 解析Redisson yaml文件
         */
        public org.redisson.config.Config getRedissonConfig() {
            if (redissonConfig != null) {
                return redissonConfig;
            }
            redissonConfig = RedissonConfigLoader.loadFromYaml(this.redissonYamlConfig);
            return redissonConfig;
        }

    }

    /**
     * 多级缓存一致性配置
     *
     * <p>这些能力默认全部关闭，开启后才会接入缓存写/删路径，避免对默认行为产生影响。
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    @ToString
    public static class ConsistencyConfig implements Config {

        /**
         * 是否启用延迟双删（evict 后延迟一段时间再删一次，缓解主从/同步延迟导致的脏数据）
         */
        private boolean delayedDoubleDelete = false;

        /**
         * 延迟双删的延迟时间（毫秒）
         */
        private long delayedDoubleDeleteMillis = 500L;

        /**
         * 是否启用删除失败补偿（evict 时 L2 删除失败则记录，由后台任务定期重试）
         * <p>需要 Redis 可用。
         */
        private boolean deleteCompensation = false;

        /**
         * 删除失败补偿的最大重试次数，超过则放弃
         */
        private int deleteCompensationMaxRetry = 10;

        /**
         * 删除失败补偿任务的执行间隔（秒）
         */
        private long deleteCompensationIntervalSeconds = 60L;

        /**
         * 是否注册主从一致性处理器 Bean（{@code MasterSlaveConsistencyHandler}，编程式使用）
         */
        private boolean masterSlave = false;
    }

    /**
     * 缓存穿透增强配置（布隆过滤器）。
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    @ToString
    public static class PenetrationConfig implements Config {

        /**
         * 是否注册布隆过滤器 Bean（默认本地 Guava 实现），用于防穿透前置拦截。默认关闭。
         * <p>开启后<b>仍需</b>对每个 cacheName 显式 {@code register/warmUp}（装入全量合法 key）才会生效；
         * 未注册的 cacheName 不受影响。见 {@code CacheBloomFilter}。
         */
        private boolean bloomEnabled = false;

        /**
         * 布隆过滤器默认预期容量（register/warmUp 未显式指定时使用）。
         */
        private long bloomExpectedInsertions = 1_000_000L;

        /**
         * 布隆过滤器默认误判率（False Positive Rate）。
         */
        private double bloomFpp = 0.01;
    }

    /**
     * 缓存同步策略配置
     */
    @Getter
    @Setter
    @Accessors(chain = true)
    @ToString
    public static class CacheSyncPolicyConfig implements Config {

        /**
         * 策略类型
         */
        private CacheMsgTypeEnum msgType;

        /**
         * 缓存更新时通知其他节点的topic名称
         */
        private String topic = "cache-sync-topic";

        /**
         * 是否支持异步发送消息
         */
        private boolean async;

        /**
         * REDIS 同步策略是否使用<b>可靠版</b>（{@code EnhancedRedisCacheSyncPolicy}）。
         * <p>默认 true：开启消息版本门控（防乱序）+ messageId 去重 + 失败补偿。
         * 设为 false 回退到基础版 {@code RedisCacheSyncPolicy}（仅 Pub/Sub，无上述保障）。
         * <p>注意：版本门控基于发布方本地时钟（毫秒时间戳），跨实例需依赖 NTP 时钟同步；
         * 时钟漂移较大时可关闭本项或改善时钟同步。仅对 {@code msgType=REDIS} 生效。
         */
        private boolean enhanced = true;

        /**
         * 可靠版策略是否启用 ACK 确认（发布后等待消费方确认，未确认则重试）。默认关闭。
         */
        private boolean enableAck = false;

        /**
         * 可靠版策略发布消息失败时的最大重试次数。默认 3。
         */
        private int maxRetries = 3;

        /**
         * 具体的属性配置
         * 定义一个通用的属性字段，不同的MQ可配置各自的属性即可。
         * 如:kafka的属性配置则完全与原生的配置保持一致
         */
        private Properties props = new Properties();
    }

}
