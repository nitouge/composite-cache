package io.github.nitouge.cache.core.sync;

import io.github.nitouge.cache.core.consts.CacheConsts;
import io.github.nitouge.cache.core.sync.listener.CacheSyncMessage;
import io.github.nitouge.cache.core.support.mdc.RunnableMdcWrapper;
import io.github.nitouge.cache.core.pool.ThreadPoolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 增强版 Redis 缓存同步策略
 *
 * <h3>增强功能</h3>
 * <ul>
 *   <li>版本号机制：防止消息乱序</li>
 *   <li>重试机制：消息发送失败自动重试</li>
 *   <li>ACK 确认：可选的消息确认机制</li>
 *   <li>失败补偿：定期重试失败的消息</li>
 *   <li>去重处理：避免重复处理相同消息</li>
 * </ul>
 *
 */
@Slf4j
public class EnhancedRedisCacheSyncPolicy extends AbstractCacheSyncPolicy<RedissonClient> {
    
    AtomicBoolean start = new AtomicBoolean(false);
    
    private RTopic rTopic;

    /** ACK 确认 topic（enableAck=true 时使用），持有引用以便 close() 时移除监听，避免重建泄漏 */
    private RTopic rAckTopic;

    /**
     * 补偿任务调度器
     */
    private ScheduledExecutorService compensateScheduler;

    /**
     * 定义静态线程池，避免高并发情况下造成锁的竞争
     */
    private static final ThreadPoolExecutor poolExecutor = ThreadPoolRegistry.getPool("publish_redis_msg");

    /**
     * 等待 ACK 的消息（消息 ID -> Future）
     */
    private final ConcurrentHashMap<String, CompletableFuture<Boolean>> pendingAcks = new ConcurrentHashMap<>();

    /**
     * 失败的消息（用于补偿）
     */
    private final ConcurrentHashMap<String, CacheSyncMessage> failedMessages = new ConcurrentHashMap<>();

    /**
     * 已处理的消息 ID（用于去重）
     * <p>使用 LRU 缓存，最多保留 10000 条
     */
    private final Map<String, Long> processedMessages = java.util.Collections.synchronizedMap(
        new LinkedHashMap<String, Long>(10000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > 10000;
            }
        }
    );

    /**
     * 每个 key 已处理的最大版本号（用于防止消息乱序）
     * <p>version 为发布方的发布时间戳（毫秒）。同一 key 只处理版本号<b>严格大于</b>已处理版本的消息，
     * 从而保证"后写覆盖先写"，丢弃迟到的旧消息
     * <p>使用 LRU 缓存，最多保留 10000 个 key，防止内存无限增长
     * <p><b>限制</b>：版本号基于发布方的本地时钟，跨实例存在时钟漂移风险（依赖 NTP 同步）
     */
    private final Map<String, Long> keyVersions = java.util.Collections.synchronizedMap(
        new LinkedHashMap<String, Long>(10000, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > 10000;
            }
        }
    );
    
    /**
     * 实例 ID
     */
    private String instanceId;

    /**
     * 是否启用 ACK 确认
     */
    private boolean enableAck = false;

    /**
     * 最大重试次数
     */
    private int maxRetries = 3;
    
    @Override
    public void run() {
        if (!start.compareAndSet(false, true)) {
            log.info("Enhanced cache sync policy already started");
            return;
        }
        
        // 使用全局配置的实例 ID
        instanceId = this.getCacheConfig().getInstanceId();
        
        RedissonClient redissonClient = this.getActualClient();
        String topicName = this.getCacheConfig().getCacheSyncPolicy().getTopic();

        rTopic = redissonClient.getTopic(topicName);
        rTopic.addListenerAsync(CacheSyncMessage.class, (channel, msg) -> this.onMessage(msg));

        if (enableAck) {
            rAckTopic = redissonClient.getTopic(topicName + ":ack");
            rAckTopic.addListenerAsync(String.class, (channel, messageId) -> this.onAck(messageId));
        }

        log.info("Enhanced cache sync policy started, instanceId={}, enableAck={}", instanceId, enableAck);

        // 启动补偿任务调度器（每分钟执行一次）
        compensateScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-sync-compensate");
            t.setDaemon(true);
            return t;
        });
        compensateScheduler.scheduleAtFixedRate(this::compensateFailedMessages, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 发布消息（带重试）
     */
    @Override
    public void publish(CacheSyncMessage message) {
        Long publishMsgPeriodMilliSeconds = this.getCacheConfig().getCaffeine().getPublishMsgPeriodMilliSeconds();
        boolean async = this.getCacheConfig().getCacheSyncPolicy().isAsync();
        
        // 设置增强字段
        if (message.getMessageId() == null) {
            message.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        }
        message.setInstanceId(instanceId);
        message.setVersion(System.currentTimeMillis());
        message.setCreateTime(System.currentTimeMillis());
        message.setRetryCount(0);
        
        if (async) {
            poolExecutor.execute(new RunnableMdcWrapper(() -> {
                publishWithRetry(message, maxRetries, publishMsgPeriodMilliSeconds);
            }, message));
        } else {
            publishWithRetry(message, maxRetries, publishMsgPeriodMilliSeconds);
        }
    }
    
    /**
     * 带重试的发布消息
     */
    private void publishWithRetry(CacheSyncMessage message, int maxRetries, long lockTimeout) {
        int retries = 0;

        while (retries < maxRetries) {
            try {
                RedissonClient redissonClient = this.getActualClient();
                String lockName = message.getCacheName() + CacheConsts.SPLIT_SINGLE
                        + message.getKey() + CacheConsts.SPLIT_SINGLE
                        + message.getCacheSyncType().name();

                RLock lock = redissonClient.getLock(lockName);
                try {
                    if (!lock.tryLock(0, lockTimeout, TimeUnit.MILLISECONDS)) {
                        log.debug("Failed to acquire lock, skip publish, key={}", message.getKey());
                        return;
                    }

                    long receivedClients = this.rTopic.publish(message);
                    log.debug("Cache sync message sent, messageId={}, receivedClients={}, version={}",
                            message.getMessageId(), receivedClients, message.getVersion());

                    if (enableAck) {
                        boolean acked = waitForAck(message.getMessageId(), 1000);
                        if (acked) {
                            log.debug("Cache sync message acked, messageId={}", message.getMessageId());
                            return;
                        } else {
                            log.warn("Wait for ack timeout, messageId={}, retry={}", message.getMessageId(), retries);
                        }
                    } else {
                        return;
                    }
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }

                retries++;

            } catch (Exception e) {
                retries++;
                log.warn("Failed to send cache sync message, retry {}/{}, messageId={}",
                        retries, maxRetries, message.getMessageId(), e);

                if (retries >= maxRetries) {
                    message.setRetryCount(retries);
                    failedMessages.put(message.getMessageId(), message);
                    log.error("Failed to send cache sync message after {} retries, messageId={}",
                            maxRetries, message.getMessageId());
                    return;
                }

                try {
                    Thread.sleep(100L * retries);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
    
    /**
     * 接收消息（检查版本号）
     */
    private void onMessage(CacheSyncMessage message) {
        try {
            // 1. 忽略自己发送的消息
            if (instanceId.equals(message.getInstanceId())) {
                log.debug("Ignore message from self, messageId={}", message.getMessageId());
                return;
            }
            
            // 2. 去重检查
            if (isDuplicate(message.getMessageId())) {
                log.debug("Ignore duplicate message, messageId={}", message.getMessageId());
                return;
            }
            
            // 3. 检查版本号（防止消息乱序）：仅处理比已处理版本更新的消息
            if (isStaleVersion(message)) {
                log.debug("Ignore stale (out-of-order) message, messageId={}, key={}, version={}",
                        message.getMessageId(), message.getKey(), message.getVersion());
                return;
            }

            // 4. 处理消息
            this.getCacheMessageListener().onMessage(message);

            // 5. 处理成功后才记录已处理消息 + 提交版本号
            // 失败时不提交：补偿/重发的同版本消息可被重新处理，避免 L1 永久脏数据
            processedMessages.put(message.getMessageId(), System.currentTimeMillis());
            commitVersion(message);

            // 6. 发送 ACK 确认
            if (enableAck) {
                sendAck(message.getMessageId());
            }
            
            log.debug("Cache sync message processed, messageId={}, key={}, version={}", 
                     message.getMessageId(), message.getKey(), message.getVersion());
            
        } catch (Exception e) {
            log.error("Failed to process cache sync message, messageId={}", message.getMessageId(), e);
        }
    }
    
    /**
     * 等待 ACK 确认
     */
    private boolean waitForAck(String messageId, long timeoutMs) {
        CompletableFuture<Boolean> ackFuture = new CompletableFuture<>();
        pendingAcks.put(messageId, ackFuture);

        try {
            return ackFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.debug("Wait for ack timeout, messageId={}", messageId);
            return false;
        } catch (Exception e) {
            log.warn("Wait for ack failed, messageId={}", messageId, e);
            return false;
        } finally {
            pendingAcks.remove(messageId);
        }
    }

    /**
     * 发送 ACK 确认
     */
    private void sendAck(String messageId) {
        try {
            RedissonClient redissonClient = this.getActualClient();
            String topicName = this.getCacheConfig().getCacheSyncPolicy().getTopic() + ":ack";
            RTopic ackTopic = redissonClient.getTopic(topicName);
            ackTopic.publish(messageId);
            log.debug("ACK sent, messageId={}", messageId);
        } catch (Exception e) {
            log.warn("Failed to send ack, messageId={}", messageId, e);
        }
    }

    /**
     * 接收 ACK 确认
     */
    private void onAck(String messageId) {
        CompletableFuture<Boolean> ackFuture = pendingAcks.get(messageId);
        if (ackFuture != null) {
            ackFuture.complete(true);
            log.debug("ACK received, messageId={}", messageId);
        }
    }
    
    /**
     * 检查是否重复消息
     */
    private boolean isDuplicate(String messageId) {
        return processedMessages.containsKey(messageId);
    }

    /**
     * 版本号检查：判断消息是否为迟到的旧消息（防止乱序）
     *
     * <p>对同一个 key，仅当消息版本号<b>严格大于</b>已处理的最大版本号时才认为是新消息；
     * 否则视为乱序/过期消息予以丢弃，保证"后写覆盖先写"的最终一致性
     *
     * <p>对于无 key 的全局操作（如 CLEAR），不做版本门控，始终放行
     *
     * @param message 缓存同步消息
     * @return true 表示是过期/乱序消息（应丢弃），false 表示是更新的消息（应处理）
     */
    private boolean isStaleVersion(CacheSyncMessage message) {
        // 全局操作（key 为空，如 CLEAR）不做版本门控
        if (message.getKey() == null) {
            return false;
        }
        long incomingVersion = message.getVersion();
        // version 默认值为 0 表示发布方未设置版本号，跳过门控以保持兼容
        if (incomingVersion <= 0) {
            return false;
        }
        String versionKey = message.getCacheName() + CacheConsts.SPLIT_SINGLE + message.getKey();
        synchronized (keyVersions) {
            Long processedVersion = keyVersions.get(versionKey);
            // 只读判断：迟到/重复（版本号 <= 已提交版本）即为过期消息。不在此更新版本
            // 版本号改由处理成功后的 commitVersion 提交（见 onMessage 第 5 步），避免处理失败仍门控住后续重发
            return processedVersion != null && incomingVersion <= processedVersion;
        }
    }

    /**
     * 处理成功后提交版本号（单调递增地记录每个 key 已处理的最大版本）
     *
     * <p>与 {@link #isStaleVersion} 拆为"先判断、后提交"两阶段：监听器处理失败时<b>不提交</b>版本，
     * 否则补偿/重发的同版本消息会被误判为 stale 丢弃，导致 L1 永久脏数据
     */
    private void commitVersion(CacheSyncMessage message) {
        if (message.getKey() == null) {
            return;
        }
        long incomingVersion = message.getVersion();
        if (incomingVersion <= 0) {
            return;
        }
        String versionKey = message.getCacheName() + CacheConsts.SPLIT_SINGLE + message.getKey();
        synchronized (keyVersions) {
            Long processedVersion = keyVersions.get(versionKey);
            if (processedVersion == null || incomingVersion > processedVersion) {
                keyVersions.put(versionKey, incomingVersion);
            }
        }
    }
    
    /**
     * 补偿失败的消息（由内部调度器定期执行）
     */
    private void compensateFailedMessages() {
        if (failedMessages.isEmpty()) {
            return;
        }

        // 先取快照并清空待补偿集合：republish 成功/放弃的不再入队，而 republish 仍失败的会由
        // publishWithRetry 重新放回 failedMessages，等待下一轮。
        // （修复旧实现：republish 后无条件 iterator.remove()，会把补偿仍失败的消息也一并丢弃——补偿只生效一轮。）
        List<CacheSyncMessage> snapshot = new ArrayList<>(failedMessages.values());
        failedMessages.clear();
        log.info("Start to compensate failed messages, size={}", snapshot.size());

        Long publishMsgPeriodMilliSeconds = this.getCacheConfig().getCaffeine().getPublishMsgPeriodMilliSeconds();
        int giveUp = 0;
        for (CacheSyncMessage message : snapshot) {
            try {
                // 检查消息是否过期（超过1小时）：放弃（已从集合移除，不再重发）
                if (System.currentTimeMillis() - message.getCreateTime() > 3600000) {
                    giveUp++;
                    log.warn("Give up compensate expired message, messageId={}", message.getMessageId());
                    continue;
                }

                // 重新发送：刻意保留消息<b>原始 version</b>（代表该次写的逻辑时间戳）。
                // 若期间已有更新的同 key 消息被接收方处理，本条会被正确判为 stale 丢弃（后写覆盖先写；
                // 且这些是 EVICT/REFRESH 等幂等失效消息，丢弃已被更新覆盖的失效无副作用）。
                // 不 bump version——否则旧 evict 会伪装成更新消息、破坏 version 排序语义。
                // republish 若再次失败，publishWithRetry 会把它重新入队 failedMessages，等待下一轮。
                publishWithRetry(message, 3, publishMsgPeriodMilliSeconds);

            } catch (Exception e) {
                log.error("Compensate message failed, messageId={}", message.getMessageId(), e);
            }
        }
        log.info("Compensate round done, processed={}, requeued={}, giveUp={}", snapshot.size(), failedMessages.size(), giveUp);
    }
    
    
    @Override
    public void close() {
        // 关闭补偿调度器
        if (compensateScheduler != null && !compensateScheduler.isShutdown()) {
            compensateScheduler.shutdown();
        }
        // 移除 RTopic 监听器，避免上下文关闭/重建时同一 topic 累积多个监听器（监听器泄漏）
        if (rTopic != null) {
            try {
                rTopic.removeAllListeners();
            } catch (Exception e) {
                log.warn("Failed to remove rTopic listeners on close", e);
            }
        }
        if (rAckTopic != null) {
            try {
                rAckTopic.removeAllListeners();
            } catch (Exception e) {
                log.warn("Failed to remove ack topic listeners on close", e);
            }
        }
        // 清理资源
        pendingAcks.clear();
        failedMessages.clear();
        processedMessages.clear();
        // 允许干净地重新 run()（run() 以 start CAS 守卫，不重置则重建后无法再启动）
        start.set(false);
    }
    
    // ==================== Getter/Setter ====================
    
    public void setEnableAck(boolean enableAck) {
        this.enableAck = enableAck;
    }
    
    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
