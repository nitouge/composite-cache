package io.github.nitouge.cache.test.service;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.nitouge.cache.core.consistency.CacheDeleteCompensation;
import io.github.nitouge.cache.core.consistency.MasterSlaveConsistencyHandler;
import io.github.nitouge.cache.test.entity.Stock;
import io.github.nitouge.cache.test.mapper.StockMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 生产级缓存服务（综合方案）
 *
 * <h3>核心策略</h3>
 * <ul>
 *   <li>主从延迟：写标记 + 强制读主库</li>
 *   <li>并发控制：分布式锁 + 请求合并</li>
 *   <li>缓存击穿：逻辑过期 + 异步刷新</li>
 *   <li>一致性保证：失败记录 + 定时补偿 + 版本号追踪</li>
 * </ul>
 *
 * <h3>适用场景</h3>
 * <pre>
 * 交易系统、库存系统等对一致性和性能都有要求的场景
 *
 * 特点：
 * - 强一致性：写标记保证主从延迟期间读主库
 * - 高性能：逻辑过期避免缓存击穿
 * - 高可用：失败补偿保证最终一致性
 * - 可追踪：版本号追踪，检测不一致
 * </pre>
 *
 * <h3>说明（重要）</h3>
 * <p>本类是<b>手工综合实现，仅作对照/进阶参考</b>。<b>生产中推荐直接用框架能力，无需手搓</b>：
 * 逻辑过期 + 异步单飞刷新已由 {@code redis.loadStrategy=LOGICAL_EXPIRE} 内置（{@code RedissonRBucketCache}
 * 用 Redisson RLock 做正确的跨实例单飞刷新）；防穿透/雪崩/降级/删除补偿/主从读主标记也都有现成开关与组件，
 * 配合 {@code @CacheAble} / {@code CacheTemplate} 即可。{@code checkConsistency}/{@code fixInconsistency}
 * 等方法仅作演示。
 *
 */
@Service
@Slf4j
public class ProductionCacheService {

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StockMapper stockMapper;

    @Autowired
    private MasterSlaveConsistencyHandler masterSlaveHandler;

    @Autowired
    private CacheDeleteCompensation deleteCompensation;
    
    /**
     * 缓存名称
     */
    private static final String CACHE_NAME = "stock";
    
    /**
     * 版本号Key前缀
     */
    private static final String VERSION_KEY_PREFIX = "stock:version:";
    
    /**
     * 逻辑过期时间（1小时）
     */
    private static final long LOGICAL_EXPIRE_TIME = 3600000;
    
    /**
     * 刷新线程池
     */
    private final ThreadPoolExecutor refreshExecutor = new ThreadPoolExecutor(
        5, 10, 60, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(1000),
        new ThreadFactoryBuilder()
            .setNameFormat("production-cache-refresh-%d")
            .setDaemon(true)
            .build(),
        new ThreadPoolExecutor.DiscardPolicy() // 刷新为尽力而为：队列满时直接丢弃，不影响主流程、不抛异常
    );
    
    /**
     * 带版本号和逻辑过期的缓存值
     */
    @Data
    public static class CacheValue<T> {
        private T data;
        private long version;
        private long logicalExpireTime;
        private long createTime;
        
        public boolean isExpired() {
            return System.currentTimeMillis() > logicalExpireTime;
        }
        
        public long getAge() {
            return System.currentTimeMillis() - createTime;
        }
    }
    
    /**
     * 查询库存（完整方案）
     * 
     * @param productId 商品ID
     * @return 库存信息
     */
    public Stock getStock(Long productId) {
        String key = CACHE_NAME + ":" + productId;
        String versionKey = VERSION_KEY_PREFIX + productId;
        
        // 1. 检查写标记（主从延迟保护）
        boolean hasWriteFlag = masterSlaveHandler.hasWriteFlag(CACHE_NAME, productId);

        if (hasWriteFlag) {
            // 刚被写入，强制读主库
            Stock stock = stockMapper.selectByProductId(productId);

            // 更新缓存
            if (stock != null) {
                long currentVersion = redissonClient.getAtomicLong(versionKey).get();
                putCache(productId, stock, currentVersion);
            }

            log.info("Read from master due to write flag, productId={}", productId);
            return stock;
        }
        
        // 2. 查询缓存
        RBucket<CacheValue<Stock>> bucket = redissonClient.getBucket(key);
        CacheValue<Stock> cacheValue = bucket.get();
        
        // 3. 缓存未命中
        if (cacheValue == null) {
            return loadAndCacheWithLock(productId);
        }
        
        // 4. 检查版本号
        long currentVersion = redissonClient.getAtomicLong(versionKey).get();
        if (cacheValue.getVersion() != currentVersion) {
            log.warn("Version mismatch, productId={}, cache={}, current={}", 
                productId, cacheValue.getVersion(), currentVersion);
            
            // 版本不一致，重新加载
            return loadAndCacheWithLock(productId);
        }
        
        // 5. 检查逻辑过期：返回旧值 + 异步刷新（永不阻塞、永不击穿）
        if (cacheValue.isExpired()) {
            log.info("Cache logically expired, productId={}, age={}ms",
                productId, cacheValue.getAge());
            // 用分布式锁做异步刷新单飞：只放一个请求/实例去刷新，避免重复回源。
            // （原先的 synchronized(cacheValue) 无效——cacheValue 是每次从 Redis 反序列化出的新对象，
            //  不同请求锁的是不同对象，且 refreshing 标记不会写回 Redis。框架的 LOGICAL_EXPIRE 已内置正确单飞。）
            refreshExecutor.execute(() -> tryRefreshWithLock(productId));
        }
        
        // 6. 返回缓存值
        return cacheValue.getData();
    }
    
    /**
     * 更新库存（完整方案）
     *
     * @param productId 商品ID
     * @param quantity 库存数量
     * @return 是否更新成功
     */
    @Transactional
    public boolean updateStock(Long productId, Integer quantity) {
        String versionKey = VERSION_KEY_PREFIX + productId;

        // 1. 查询当前版本号
        Stock currentStock = stockMapper.selectByProductId(productId);
        if (currentStock == null) {
            log.warn("Stock not found, productId={}", productId);
            return false;
        }

        // 2. 更新数据库（乐观锁）
        int updated = stockMapper.updateStockWithVersion(productId, quantity, currentStock.getVersion());
        if (updated == 0) {
            log.warn("Update stock failed (version conflict), productId={}, version={}",
                productId, currentStock.getVersion());
            return false;
        }

        // 3. 递增版本号计数器（用于缓存版本追踪）
        RAtomicLong versionCounter = redissonClient.getAtomicLong(versionKey);
        long newVersion = versionCounter.incrementAndGet();

        // 4. 设置写标记（3秒内强制读主库）
        masterSlaveHandler.setWriteFlag(CACHE_NAME, productId);

        // 5. 删除缓存（带失败记录）
        deleteCompensation.deleteCacheWithRecord(CACHE_NAME, productId);

        log.info("Update stock success, productId={}, quantity={}, dbVersion={}, cacheVersion={}",
            productId, quantity, currentStock.getVersion() + 1, newVersion);

        return true;
    }
    
    /**
     * 加载并缓存（带分布式锁）
     */
    private Stock loadAndCacheWithLock(Long productId) {
        String key = CACHE_NAME + ":" + productId;
        String lockKey = "lock:" + key;
        String versionKey = VERSION_KEY_PREFIX + productId;
        
        RLock lock = redissonClient.getLock(lockKey);
        
        try {
            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("Failed to acquire lock, productId={}", productId);
                return null;
            }
            
            try {
                // 双重检查
                RBucket<CacheValue<Stock>> bucket = redissonClient.getBucket(key);
                CacheValue<Stock> cacheValue = bucket.get();
                if (cacheValue != null) {
                    return cacheValue.getData();
                }

                // 查询数据库
                Stock stock = stockMapper.selectByProductId(productId);

                if (stock != null) {
                    long currentVersion = redissonClient.getAtomicLong(versionKey).get();
                    putCache(productId, stock, currentVersion);

                    log.info("Load and cache, productId={}, version={}",
                        productId, currentVersion);
                }

                return stock;
                
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
            
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while loading cache, productId={}", productId, e);
            return null;
        }
    }
    
    /**
     * 异步刷新单飞：用分布式锁保证同一 key 同一时刻只有一个刷新在进行（跨请求/跨实例）。
     * <p>在<b>执行线程内</b> tryLock/unlock（Redisson RLock 线程绑定，不能跨线程解锁），并加租约兜底防死锁。
     */
    private void tryRefreshWithLock(Long productId) {
        RLock refreshLock = redissonClient.getLock("lock:refresh:" + CACHE_NAME + ":" + productId);
        boolean locked = false;
        try {
            locked = refreshLock.tryLock(0, 30, TimeUnit.SECONDS); // 不等待；30秒租约自动释放兜底
            if (!locked) {
                return; // 已有刷新在进行，跳过
            }
            refreshCache(productId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (locked && refreshLock.isHeldByCurrentThread()) {
                refreshLock.unlock();
            }
        }
    }

    /**
     * 异步刷新缓存
     */
    private void refreshCache(Long productId) {
        String versionKey = VERSION_KEY_PREFIX + productId;
        try {
            log.info("Start async refresh, productId={}", productId);

            // 查询数据库
            Stock stock = stockMapper.selectByProductId(productId);

            if (stock != null) {
                long currentVersion = redissonClient.getAtomicLong(versionKey).get();
                putCache(productId, stock, currentVersion);

                log.info("Async refresh success, productId={}, version={}", productId, currentVersion);
            }
        } catch (Exception e) {
            log.error("Async refresh failed, productId={}", productId, e);
        }
    }
    
    /**
     * 写入缓存
     */
    private void putCache(Long productId, Stock stock, long version) {
        String key = CACHE_NAME + ":" + productId;
        
        CacheValue<Stock> cacheValue = new CacheValue<>();
        cacheValue.setData(stock);
        cacheValue.setVersion(version);
        cacheValue.setLogicalExpireTime(System.currentTimeMillis() + LOGICAL_EXPIRE_TIME);
        cacheValue.setCreateTime(System.currentTimeMillis());
        
        RBucket<CacheValue<Stock>> bucket = redissonClient.getBucket(key);
        bucket.set(cacheValue);
    }
    
    /**
     * 预热缓存
     *
     * @param productId 商品ID
     */
    public void warmup(Long productId) {
        Stock stock = stockMapper.selectByProductId(productId);

        if (stock != null) {
            String versionKey = VERSION_KEY_PREFIX + productId;
            long currentVersion = redissonClient.getAtomicLong(versionKey).get();

            putCache(productId, stock, currentVersion);

            log.info("Warmup cache, productId={}, version={}", productId, currentVersion);
        }
    }
    
    /**
     * 检查缓存一致性
     * 
     * @param productId 商品ID
     * @return 一致性检查结果
     */
    public ConsistencyCheckResult checkConsistency(Long productId) {
        String key = CACHE_NAME + ":" + productId;
        
        // 1. 查询缓存
        RBucket<CacheValue<Stock>> bucket = redissonClient.getBucket(key);
        CacheValue<Stock> cacheValue = bucket.get();
        Stock cacheStock = cacheValue != null ? cacheValue.getData() : null;
        
        // 2. 查询数据库
        Stock dbStock = stockMapper.selectByProductId(productId);
        
        // 3. 对比
        ConsistencyCheckResult result = new ConsistencyCheckResult();
        result.setProductId(productId);
        result.setCacheStock(cacheStock);
        result.setDbStock(dbStock);
        
        if (cacheStock == null && dbStock == null) {
            result.setConsistent(true);
            result.setMessage("Both null");
        } else if (cacheStock == null || dbStock == null) {
            result.setConsistent(false);
            result.setMessage("One is null");
        } else if (!cacheStock.equals(dbStock)) {
            result.setConsistent(false);
            result.setMessage("Data mismatch");
            
            // 检查版本号
            if (cacheValue != null) {
                String versionKey = VERSION_KEY_PREFIX + productId;
                long currentVersion = redissonClient.getAtomicLong(versionKey).get();
                result.setCacheVersion(cacheValue.getVersion());
                result.setCurrentVersion(currentVersion);
            }
        } else {
            result.setConsistent(true);
            result.setMessage("Consistent");
        }
        
        return result;
    }
    
    /**
     * 修复不一致
     * 
     * @param productId 商品ID
     */
    public void fixInconsistency(Long productId) {
        // 检查一致性
        ConsistencyCheckResult result = checkConsistency(productId);
        
        if (result.isConsistent()) {
            log.info("Cache is consistent, no need to fix, productId={}", productId);
            return;
        }
        
        // 删除缓存（让下次查询重新加载）
        deleteCompensation.deleteCacheWithRecord(CACHE_NAME, productId);
        
        log.info("Fixed inconsistency by deleting cache, productId={}", productId);
    }
    
    /**
     * 一致性检查结果
     */
    @Data
    public static class ConsistencyCheckResult {
        private Long productId;
        private Stock cacheStock;
        private Stock dbStock;
        private boolean consistent;
        private String message;
        private long cacheVersion;
        private long currentVersion;
    }
}
