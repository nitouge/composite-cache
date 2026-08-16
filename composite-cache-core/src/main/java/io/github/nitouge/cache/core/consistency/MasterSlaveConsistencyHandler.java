package io.github.nitouge.cache.core.consistency;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 主从延迟一致性处理器
 * 
 * <h3>核心策略</h3>
 * <ul>
 *   <li>写标记：更新后设置标记，短时间内强制读主库</li>
 *   <li>标记TTL：默认3秒，覆盖主从延迟时间</li>
 *   <li>自动降级：标记过期后恢复读从库</li>
 * </ul>
 * 
 * <h3>使用场景</h3>
 * <pre>
 * 交易系统、库存系统等对一致性要求高的场景
 * 
 * 场景：
 * T0: 更新库存（写主库）
 * T1: 设置写标记（3秒TTL）
 * T2: 删除缓存
 * T3: 用户查询（检测到写标记）→ 强制读主库
 * T4: 写标记过期 → 恢复读从库
 * </pre>
 * 
 */
@Slf4j
public class MasterSlaveConsistencyHandler {

    private final RedissonClient redissonClient;

    /**
     * @param redissonClient Redisson 客户端（可为 null，此时所有写标记操作降级为空操作）
     */
    public MasterSlaveConsistencyHandler(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 写标记前缀
     */
    private static final String WRITE_FLAG_PREFIX = "cache:write:flag:";
    
    /**
     * 默认写标记TTL（毫秒）
     */
    private static final int DEFAULT_WRITE_FLAG_TTL = 3000;
    
    /**
     * 设置写标记
     * 
     * @param cacheName 缓存名称
     * @param key 缓存Key
     */
    public void setWriteFlag(String cacheName, Object key) {
        setWriteFlag(cacheName, key, DEFAULT_WRITE_FLAG_TTL);
    }
    
    /**
     * 设置写标记（自定义TTL）
     * 
     * @param cacheName 缓存名称
     * @param key 缓存Key
     * @param ttlMillis TTL（毫秒）
     */
    public void setWriteFlag(String cacheName, Object key, int ttlMillis) {
        if (redissonClient == null) {
            log.debug("RedissonClient not available, skip write flag");
            return;
        }
        
        try {
            String flagKey = buildFlagKey(cacheName, key);
            RBucket<String> bucket = redissonClient.getBucket(flagKey);
            bucket.set("1", Duration.ofMillis(ttlMillis));

            log.debug("Set write flag, key={}, ttl={}ms", flagKey, ttlMillis);
            
        } catch (Exception e) {
            log.error("Failed to set write flag, cacheName={}, key={}", cacheName, key, e);
        }
    }
    
    /**
     * 检查是否有写标记
     * 
     * @param cacheName 缓存名称
     * @param key 缓存Key
     * @return true=有写标记（需要读主库），false=无写标记（可以读从库）
     */
    public boolean hasWriteFlag(String cacheName, Object key) {
        if (redissonClient == null) {
            return false;
        }
        
        try {
            String flagKey = buildFlagKey(cacheName, key);
            RBucket<String> bucket = redissonClient.getBucket(flagKey);
            boolean exists = bucket.isExists();
            
            if (exists) {
                log.debug("Write flag exists, need to read from master, key={}", flagKey);
            }
            
            return exists;
            
        } catch (Exception e) {
            log.error("Failed to check write flag, cacheName={}, key={}", cacheName, key, e);
            return false;
        }
    }
    
    /**
     * 删除写标记
     * 
     * @param cacheName 缓存名称
     * @param key 缓存Key
     */
    public void removeWriteFlag(String cacheName, Object key) {
        if (redissonClient == null) {
            return;
        }
        
        try {
            String flagKey = buildFlagKey(cacheName, key);
            RBucket<String> bucket = redissonClient.getBucket(flagKey);
            bucket.delete();
            
            log.debug("Remove write flag, key={}", flagKey);
            
        } catch (Exception e) {
            log.error("Failed to remove write flag, cacheName={}, key={}", cacheName, key, e);
        }
    }
    
    /**
     * 构建标记Key
     */
    private String buildFlagKey(String cacheName, Object key) {
        return WRITE_FLAG_PREFIX + cacheName + ":" + key;
    }
}
