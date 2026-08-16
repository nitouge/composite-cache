package io.github.nitouge.cache.core.schedule;

import io.github.nitouge.cache.core.pool.DaemonThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 缓存刷新调度器支持类
 * 
 * <p>提供单例的ScheduledExecutorService用于定期刷新过期缓存。
 * 
 */
@Slf4j
public final class ExpiredCacheRefreshSupport {

    private static volatile ScheduledExecutorService scheduler;

    private ExpiredCacheRefreshSupport() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * 获取调度器实例
     *
     * <p><b>全进程共享单例调度器</b>——池大小由【第一个】调用方的 {@code corePoolSize} 决定，
     * 之后调用传入的 size 会被忽略（已初始化即直接返回现有实例）。
     *
     * @param corePoolSize 核心线程数
     * @return 调度器实例
     */
    public static ScheduledExecutorService getInstance(int corePoolSize) {
        if (scheduler != null) {
            return scheduler;
        }
        
        synchronized (ExpiredCacheRefreshSupport.class) {
            if (scheduler == null) {
                scheduler = createScheduler(corePoolSize);
                log.info("ExpiredCacheRefreshSupport scheduler initialized, corePoolSize={}", corePoolSize);
            }
        }
        return scheduler;
    }

    /**
     * 创建调度器
     */
    private static ScheduledExecutorService createScheduler(int corePoolSize) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
                corePoolSize,
                new DaemonThreadFactory("cache-refresh-"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // 设置在关闭时取消定期任务
        executor.setRemoveOnCancelPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        
        return executor;
    }

    /**
     * 关闭调度器
     */
    public static void shutdown() {
        if (scheduler != null) {
            synchronized (ExpiredCacheRefreshSupport.class) {
                if (scheduler != null) {
                    scheduler.shutdown();
                    log.info("ExpiredCacheRefreshSupport scheduler shutdown");
                    scheduler = null;
                }
            }
        }
    }
}
