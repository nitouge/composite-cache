package io.github.nitouge.cache.core.sync;

import io.github.nitouge.cache.core.consts.CacheConsts;
import io.github.nitouge.cache.core.sync.listener.CacheSyncMessage;
import io.github.nitouge.cache.core.support.mdc.RunnableMdcWrapper;
import io.github.nitouge.cache.core.pool.ThreadPoolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Redis PubSub 的缓存同步策略
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>基于 Redis 发布订阅机制实现分布式缓存同步</li>
 *   <li>基于 Redisson 实现（RTopic 发布/订阅）</li>
 *   <li>支持同步/异步消息发布</li>
 *   <li>使用分布式锁防止消息重复发送</li>
 * </ul>
 *
 * <h3>Redis 缓冲区配置</h3>
 * <p>使用该策略时需注意 Redis 缓冲区配置，避免缓冲区溢出导致连接断开：
 * <pre>
 * # 消息订阅频道的客户端缓冲区配置
 * client-output-buffer-limit pubsub 32mb 8mb 60
 *
 * 参数说明：
 * - 32mb: 缓冲区大小上限，超过则断开连接
 * - 8mb: 持续写入的最大内存
 * - 60: 持续写入的最长时间（秒）
 * </pre>
 *
 * <h3>优化建议</h3>
 * <ul>
 *   <li>避免使用 bigkey（本框架消息体仅包含 key，不包含 value）</li>
 *   <li>合理设置缓冲区上限和持续写入时间</li>
 *   <li>监控 Redis 连接状态和缓冲区使用情况</li>
 * </ul>
 *
 */
@Slf4j
public class RedisCacheSyncPolicy extends AbstractCacheSyncPolicy<RedissonClient> {

    private final AtomicBoolean started = new AtomicBoolean(false);

    private RTopic rTopic;

    /**
     * 异步发布消息的线程池
     */
    private static final ThreadPoolExecutor PUBLISH_EXECUTOR = ThreadPoolRegistry.getPool("redis-msg-publish");

    @Override
    public void run() {
        if (!started.compareAndSet(false, true)) {
            log.warn("RedisCacheSyncPolicy already started, skip initialization");
            return;
        }

        try {
            RedissonClient redissonClient = this.getActualClient();
            String topicName = this.getCacheConfig().getCacheSyncPolicy().getTopic();

            rTopic = redissonClient.getTopic(topicName);
            rTopic.addListenerAsync(CacheSyncMessage.class, (channel, msg) -> {
                try {
                    this.getCacheMessageListener().onMessage(msg);
                } catch (Exception e) {
                    log.error("Failed to process cache sync message, topic={}", topicName, e);
                }
            });

            log.info("RedisCacheSyncPolicy started successfully, topic={}", topicName);
        } catch (Exception e) {
            started.set(false);
            log.error("Failed to start RedisCacheSyncPolicy", e);
            throw new RuntimeException("Failed to start RedisCacheSyncPolicy", e);
        }
    }

    @Override
    public void publish(CacheSyncMessage message) {
        if (message == null) {
            log.warn("Cache sync message is null, skip publish");
            return;
        }

        boolean async = this.getCacheConfig().getCacheSyncPolicy().isAsync();

        if (async) {
            PUBLISH_EXECUTOR.execute(new RunnableMdcWrapper(() -> doPublish(message), message));
        } else {
            doPublish(message);
        }
    }

    /**
     * 实际发布逻辑：用分布式锁去重
     *
     * <p>以 {@code cacheName:key:syncType} 为锁名，{@code tryLock(0, lockTimeout)} 即不等待、租约 lockTimeout 毫秒
     * （取自 {@code caffeine.publishMsgPeriodMilliSeconds}）；抢不到锁说明短时间内同一失效消息已被其它线程发布，本次直接跳过，
     * 避免对同一 key 的高频更新风暴式广播。任何异常仅记日志、不向上抛，保证同步失败不影响业务
     */
    private void doPublish(CacheSyncMessage message) {
        try {
            RedissonClient redissonClient = this.getActualClient();
            String topicName = this.getCacheConfig().getCacheSyncPolicy().getTopic();
            long lockTimeout = this.getCacheConfig().getCaffeine().getPublishMsgPeriodMilliSeconds();
            String lockName = buildLockName(message);

            RLock lock = redissonClient.getLock(lockName);
            boolean locked = false;

            try {
                locked = lock.tryLock(0, lockTimeout, TimeUnit.MILLISECONDS);
                if (!locked) {
                    log.debug("Failed to acquire lock, skip publish, lockName={}", lockName);
                    return;
                }

                long receivedClients = this.rTopic.publish(message);
                log.debug("Cache sync message published, topic={}, receivedClients={}", topicName, receivedClients);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Interrupted while publishing message, lockName={}", lockName, e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (Exception e) {
            log.error("Failed to publish cache sync message", e);
        }
    }

    private String buildLockName(CacheSyncMessage message) {
        return message.getCacheName() + CacheConsts.SPLIT_SINGLE +
               message.getKey() + CacheConsts.SPLIT_SINGLE +
               message.getCacheSyncType().name();
    }

    @Override
    public void close() {
        if (started.compareAndSet(true, false)) {
            try {
                if (rTopic != null) {
                    rTopic.removeAllListeners();
                }
                log.info("RedisCacheSyncPolicy closed successfully");
            } catch (Exception e) {
                log.error("Failed to close RedisCacheSyncPolicy", e);
            }
        }
    }

}
