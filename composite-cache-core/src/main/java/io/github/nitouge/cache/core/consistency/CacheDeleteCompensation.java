package io.github.nitouge.cache.core.consistency;

import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import lombok.Data;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RQueue;
import org.redisson.api.RedissonClient;

import java.io.Serializable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 缓存删除失败补偿机制
 * 
 * <h3>核心策略</h3>
 * <ul>
 *   <li>失败记录：删除失败时记录到Redis List</li>
 *   <li>定时补偿：每分钟重试失败的删除操作</li>
 *   <li>最大重试：超过10次放弃，避免无限重试</li>
 *   <li>监控告警：记录失败次数，触发告警</li>
 * </ul>
 * 
 * <h3>使用场景</h3>
 * <pre>
 * 缓存删除失败场景：
 * T0: 更新数据库成功
 * T1: 删除缓存失败（网络抖动/Redis宕机）
 * T2: 记录失败到Redis List
 * T3: 定时任务补偿（每分钟）
 * T4: 重试成功，移除记录
 * </pre>
 * 
 */
@Slf4j
public class CacheDeleteCompensation implements AutoCloseable {

    private final RedissonClient redissonClient;

    /**
     * 缓存管理器（用于补偿重试时执行真正的 evict）。
     * <p>采用 setter 注入以打破与 CacheManager 之间的循环依赖。
     */
    @Setter
    private CacheManager cacheManager;

    /**
     * 失败记录队列 Key（底层为 Redis List，使用 {@link RQueue} 的原子 poll/add 消费，支持多实例安全补偿）
     */
    private static final String FAILED_DELETE_LIST = "cache:failed:delete";

    /**
     * 最大重试次数
     */
    private final int maxRetryCount;

    /**
     * 补偿任务调度器（自带守护线程，无需宿主应用开启 @EnableScheduling）
     */
    private final ScheduledExecutorService scheduler;

    /**
     * 创建删除失败补偿器，并启动后台补偿任务。
     *
     * @param redissonClient          Redisson 客户端（可为 null，此时仅退化为不记录）
     * @param maxRetryCount           最大重试次数
     * @param compensateIntervalSeconds 补偿任务执行间隔（秒）
     */
    public CacheDeleteCompensation(RedissonClient redissonClient, int maxRetryCount, long compensateIntervalSeconds) {
        this.redissonClient = redissonClient;
        this.maxRetryCount = maxRetryCount > 0 ? maxRetryCount : 10;
        long interval = compensateIntervalSeconds > 0 ? compensateIntervalSeconds : 60L;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-delete-compensate");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleAtFixedRate(this::compensateFailedDeletes, interval, interval, TimeUnit.SECONDS);
        log.info("CacheDeleteCompensation started, maxRetry={}, intervalSeconds={}", this.maxRetryCount, interval);
    }

    /**
     * 删除缓存（带失败记录）
     * 
     * @param cacheName 缓存名称
     * @param key 缓存Key
     */
    public void deleteCacheWithRecord(String cacheName, Object key) {
        if (cacheManager == null) {
            log.warn("CacheManager not wired into CacheDeleteCompensation, cannot delete cache, cacheName={}", cacheName);
            return;
        }
        try {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                log.warn("Cache not found, cacheName={}", cacheName);
                return;
            }
            
            cache.evict(key);
            
            log.debug("Delete cache success, cacheName={}, key={}", cacheName, key);
            
        } catch (Exception e) {
            log.error("Delete cache failed, cacheName={}, key={}", cacheName, key, e);
            
            // 记录失败
            recordDeleteFailure(cacheName, key);
        }
    }
    
    /**
     * 记录删除失败
     * 
     * @param cacheName 缓存名称
     * @param key 缓存Key
     */
    public void recordDeleteFailure(String cacheName, Object key) {
        if (redissonClient == null) {
            log.warn("RedissonClient not available, cannot record delete failure");
            return;
        }
        if (key == null) {
            log.warn("Cannot record delete failure for null key, cacheName={}", cacheName);
            return;
        }

        try {
            DeleteFailureRecord record = new DeleteFailureRecord();
            record.setCacheName(cacheName);
            record.setKey(key.toString());
            record.setFailTime(System.currentTimeMillis());
            record.setRetryCount(0);
            
            // 写入 Redis 队列（FIFO，底层 List）
            RQueue<DeleteFailureRecord> queue = redissonClient.getQueue(FAILED_DELETE_LIST);
            queue.add(record);
            
            log.warn("Record delete failure, cacheName={}, key={}", cacheName, key);
            
        } catch (Exception e) {
            log.error("Failed to record delete failure, cacheName={}, key={}", cacheName, key, e);
        }
    }
    
    /**
     * 定时补偿（由内部调度器按配置的间隔执行）
     */
    public void compensateFailedDeletes() {
        if (redissonClient == null || cacheManager == null) {
            return;
        }
        
        try {
            RQueue<DeleteFailureRecord> queue = redissonClient.getQueue(FAILED_DELETE_LIST);

            int size = queue.size();
            if (size == 0) {
                return;
            }

            log.info("Start compensate failed deletes, size={}", size);

            int successCount = 0;
            int failCount = 0;
            int giveUpCount = 0;

            // 仅处理本轮快照数量：失败记录会被重新入队尾，限制次数避免本轮无限循环。
            // poll() 为原子操作（底层 LPOP），多个应用实例并发补偿时各自取到不同记录，从根本上消除
            // 旧实现 get(0)+remove(0) 非原子带来的跨实例竞争（重复处理 / 误删队首他人记录）。
            for (int i = 0; i < size; i++) {
                DeleteFailureRecord record = queue.poll();
                if (record == null) {
                    break;
                }

                try {
                    // 重试删除
                    Cache cache = cacheManager.getCache(record.getCacheName());
                    if (cache == null) {
                        // 缓存不存在：记录已被 poll 取出，直接丢弃
                        log.warn("Cache not found, drop record, cacheName={}", record.getCacheName());
                        continue;
                    }

                    cache.evict(record.getKey());
                    successCount++;
                    log.info("Compensate delete success, cacheName={}, key={}",
                        record.getCacheName(), record.getKey());

                } catch (Exception e) {
                    // 更新重试次数
                    record.setRetryCount(record.getRetryCount() + 1);
                    record.setLastRetryTime(System.currentTimeMillis());

                    if (record.getRetryCount() >= maxRetryCount) {
                        // 超过最大重试次数，放弃（记录已被 poll 取出）
                        giveUpCount++;
                        log.error("Max retry exceeded, give up, cacheName={}, key={}, retryCount={}",
                            record.getCacheName(), record.getKey(), record.getRetryCount(), e);

                        // 扩展点：达到最大重试仍失败的脏数据，可在此对接告警系统（钉钉/邮件/短信）。
                        // 框架默认仅记录 error 日志，由接入方按需扩展。

                    } else {
                        // 重新入队尾，等待下次补偿；入队自身失败则单独告警，避免静默丢失待补偿记录
                        try {
                            queue.add(record);
                            failCount++;
                            log.error("Compensate delete failed, requeued, cacheName={}, key={}, retryCount={}",
                                record.getCacheName(), record.getKey(), record.getRetryCount(), e);
                        } catch (Exception requeueEx) {
                            log.error("Compensate delete failed AND requeue failed (record lost), cacheName={}, key={}, retryCount={}",
                                record.getCacheName(), record.getKey(), record.getRetryCount(), requeueEx);
                        }
                    }
                }
            }

            log.info("Compensate completed, processed={}, success={}, fail={}, giveUp={}",
                size, successCount, failCount, giveUpCount);

        } catch (Exception e) {
            log.error("Compensate failed deletes error", e);
        }
    }
    
    /**
     * 获取失败记录数量
     * 
     * @return 失败记录数量
     */
    public int getFailedDeleteCount() {
        if (redissonClient == null) {
            return 0;
        }
        
        try {
            RQueue<DeleteFailureRecord> queue = redissonClient.getQueue(FAILED_DELETE_LIST);
            return queue.size();
        } catch (Exception e) {
            log.error("Failed to get failed delete count", e);
            return 0;
        }
    }
    
    /**
     * 清空失败记录
     */
    public void clearFailedDeletes() {
        if (redissonClient == null) {
            return;
        }
        
        try {
            RQueue<DeleteFailureRecord> queue = redissonClient.getQueue(FAILED_DELETE_LIST);
            int size = queue.size();
            queue.clear();
            
            log.info("Clear failed deletes, size={}", size);
            
        } catch (Exception e) {
            log.error("Failed to clear failed deletes", e);
        }
    }
    
    /**
     * 关闭补偿调度器（由 Spring 在销毁 Bean 时自动调用）。
     */
    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("CacheDeleteCompensation closed");
    }

    /**
     * 删除失败记录
     */
    @Data
    public static class DeleteFailureRecord implements Serializable {
        private static final long serialVersionUID = 1L;
        
        /**
         * 缓存名称
         */
        private String cacheName;
        
        /**
         * 缓存Key
         */
        private String key;
        
        /**
         * 失败时间
         */
        private long failTime;
        
        /**
         * 重试次数
         */
        private int retryCount;
        
        /**
         * 最后重试时间
         */
        private long lastRetryTime;
    }
}
