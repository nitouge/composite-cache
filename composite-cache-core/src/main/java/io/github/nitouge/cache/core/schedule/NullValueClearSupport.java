package io.github.nitouge.cache.core.schedule;

import io.github.nitouge.cache.core.pool.DaemonThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * NullValue 缓存清理调度器支持类
 *
 * <p>提供单例的 ScheduledExecutorService 用于定期清理 NullValue 缓存。
 *
 */
@Slf4j
public final class NullValueClearSupport {

    private static volatile ScheduledExecutorService scheduler;

    private static final int DEFAULT_CORE_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);

    private NullValueClearSupport() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * 获取调度器实例（使用默认线程池大小）
     *
     * @return 调度器实例
     */
    public static ScheduledExecutorService getInstance() {
        return getInstance(DEFAULT_CORE_POOL_SIZE);
    }

    /**
     * 获取调度器实例
     *
     * @param corePoolSize 核心线程数
     * @return 调度器实例
     */
    public static ScheduledExecutorService getInstance(int corePoolSize) {
        if (scheduler != null) {
            return scheduler;
        }
        
        synchronized (NullValueClearSupport.class) {
            if (scheduler == null) {
                scheduler = createScheduler(corePoolSize);
                log.info("NullValueClearSupport scheduler initialized, corePoolSize={}", corePoolSize);
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
                new DaemonThreadFactory("null-value-clear-"),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        
        // 优化配置
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
            synchronized (NullValueClearSupport.class) {
                if (scheduler != null) {
                    scheduler.shutdown();
                    log.info("NullValueClearSupport scheduler shutdown");
                    scheduler = null;
                }
            }
        }
    }
}
