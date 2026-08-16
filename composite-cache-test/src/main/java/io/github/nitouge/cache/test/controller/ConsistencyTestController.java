package io.github.nitouge.cache.test.controller;

import io.github.nitouge.cache.core.consistency.CacheDeleteCompensation;
import io.github.nitouge.cache.test.entity.Stock;
import io.github.nitouge.cache.test.service.ProductionCacheService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * 缓存一致性测试控制器
 *
 */
@RestController
@RequestMapping("/consistency")
@Slf4j
public class ConsistencyTestController {
    
    @Autowired
    private ProductionCacheService cacheService;
    
    @Autowired
    private CacheDeleteCompensation deleteCompensation;

    /** 并发测试请求数上限（防止用户可控的 threadCount 造成线程/资源耗尽）。 */
    private static final int MAX_CONCURRENT_REQUESTS = 500;

    /** 并发测试线程池的最大线程数。 */
    private static final int MAX_POOL_THREADS = 64;

    /**
     * 查询库存
     */
    @GetMapping("/stock/{productId}")
    public Stock getStock(@PathVariable Long productId) {
        long startTime = System.currentTimeMillis();
        
        Stock stock = cacheService.getStock(productId);
        
        long duration = System.currentTimeMillis() - startTime;
        log.info("获取库存, productId={}, duration={}ms", productId, duration);
        
        return stock;
    }
    
    /**
     * 更新库存
     */
    @PutMapping("/stock/{productId}")
    public Map<String, Object> updateStock(@PathVariable Long productId, 
                                           @RequestParam Integer quantity) {
        long startTime = System.currentTimeMillis();
        
        cacheService.updateStock(productId, quantity);
        
        long duration = System.currentTimeMillis() - startTime;
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("productId", productId);
        result.put("quantity", quantity);
        result.put("duration", duration);
        
        log.info("更新库存, productId={}, quantity={}, duration={}ms",
            productId, quantity, duration);
        
        return result;
    }
    
    /**
     * 预热缓存
     */
    @PostMapping("/warmup/{productId}")
    public Map<String, Object> warmup(@PathVariable Long productId) {
        cacheService.warmup(productId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("productId", productId);
        result.put("message", "预热成功");
        
        return result;
    }
    
    /**
     * 检查一致性
     */
    @GetMapping("/check/{productId}")
    public ProductionCacheService.ConsistencyCheckResult checkConsistency(@PathVariable Long productId) {
        return cacheService.checkConsistency(productId);
    }
    
    /**
     * 修复不一致
     */
    @PostMapping("/fix/{productId}")
    public Map<String, Object> fixInconsistency(@PathVariable Long productId) {
        cacheService.fixInconsistency(productId);
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("productId", productId);
        result.put("message", "修复成功");
        
        return result;
    }
    
    /**
     * 获取失败记录数量
     */
    @GetMapping("/failed-count")
    public Map<String, Object> getFailedCount() {
        int count = deleteCompensation.getFailedDeleteCount();
        
        Map<String, Object> result = new HashMap<>();
        result.put("failedCount", count);
        
        return result;
    }
    
    /**
     * 清空失败记录
     */
    @PostMapping("/clear-failed")
    public Map<String, Object> clearFailed() {
        deleteCompensation.clearFailedDeletes();
        
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "清空成功");
        
        return result;
    }
    
    /**
     * 并发测试（演示击穿保护）。
     *
     * <p>用<b>有界线程池</b>执行，并对用户可控的 {@code threadCount} 设上限（{@value #MAX_CONCURRENT_REQUESTS}），
     * 避免裸 {@code new Thread[N]} 在 N 很大时造成线程/资源耗尽（DoS）。
     */
    @GetMapping("/concurrent-test/{productId}")
    public Map<String, Object> concurrentTest(@PathVariable Long productId,
                                               @RequestParam(defaultValue = "100") Integer threadCount) {
        int requested = threadCount == null ? 0 : threadCount;
        int effective = Math.max(1, Math.min(requested, MAX_CONCURRENT_REQUESTS));
        long startTime = System.currentTimeMillis();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(effective, MAX_POOL_THREADS));
        try {
            List<Future<?>> futures = new ArrayList<>(effective);
            for (int i = 0; i < effective; i++) {
                futures.add(pool.submit(() -> {
                    Stock stock = cacheService.getStock(productId);
                    log.debug("获取库存: {}", stock);
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.warn("并发任务失败", e);
                }
            }
        } finally {
            pool.shutdown();
        }

        long duration = System.currentTimeMillis() - startTime;

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("requestedThreadCount", requested);
        result.put("effectiveThreadCount", effective);
        result.put("duration", duration);
        result.put("avgDuration", duration / (double) effective);

        log.info("并发测试完成, requested={}, effective={}, duration={}ms", requested, effective, duration);

        return result;
    }
}
