package io.github.nitouge.cache.core.pool;

import io.github.nitouge.cache.core.support.mdc.RunnableMdcWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 线程池注册中心：按名称管理隔离的业务线程池，自动透传 MDC 上下文。
 *
 * <p>同一 poolName 只会创建一个实例（首次调用的参数生效），后续相同名称的调用
 * 直接返回已有实例。若需不同配置请使用不同名称。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 使用默认配置
 * ThreadPoolExecutor pool = ThreadPoolRegistry.getPool();
 *
 * // 自定义命名 + 配置
 * ThreadPoolExecutor orderPool = ThreadPoolRegistry.getPool(
 *         "order-process", 4, 8, 60, 2000);
 *
 * // 应用关闭时统一销毁
 * ThreadPoolRegistry.shutdownAll();
 * }</pre>
 */
public final class ThreadPoolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ThreadPoolRegistry.class);

    /** 线程池注册表 */
    private static final Map<String, ThreadPoolExecutor> POOL_MAP = new ConcurrentHashMap<>(16);

    // ---- 默认配置常量 ----
    public static final String DEFAULT_POOL_NAME = "default_pool";
    public static final int DEFAULT_CORE_POOL_SIZE = 8;
    public static final int DEFAULT_MAXIMUM_POOL_SIZE = 16;
    public static final long DEFAULT_KEEPALIVE_SECONDS = 60L;
    public static final int DEFAULT_QUEUE_CAPACITY = 5000;

    private ThreadPoolRegistry() {
        // 工具类禁止实例化
    }

    // ==================== 公共 API ====================

    /** 获取默认线程池 */
    public static ThreadPoolExecutor getPool() {
        return getPool(DEFAULT_POOL_NAME);
    }

    /** 按名称获取线程池（使用默认配置） */
    public static ThreadPoolExecutor getPool(String poolName) {
        return getPool(poolName, DEFAULT_CORE_POOL_SIZE, DEFAULT_MAXIMUM_POOL_SIZE,
                DEFAULT_KEEPALIVE_SECONDS, DEFAULT_QUEUE_CAPACITY);
    }

    /** 按名称获取线程池（自定义配置） */
    public static ThreadPoolExecutor getPool(String poolName,
                                             int corePoolSize,
                                             int maximumPoolSize,
                                             long keepAliveSeconds,
                                             int queueCapacity) {
        return getPool(poolName, corePoolSize, maximumPoolSize, keepAliveSeconds,
                queueCapacity, new DefaultAbortPolicy(poolName));
    }

    /**
     * 按名称获取或创建线程池（完整参数）。
     *
     * <p><b>注意：</b>同一 poolName 仅首次调用时以传入参数创建，后续调用直接返回已有实例，
     * 传入的参数将被忽略。如需不同配置请使用不同 poolName。</p>
     *
     * @param poolName         线程池名称，不可为空
     * @param corePoolSize     核心线程数
     * @param maximumPoolSize  最大线程数
     * @param keepAliveSeconds 非核心线程空闲存活时间（秒）
     * @param queueCapacity    工作队列容量
     * @param handler          拒绝策略
     * @return 对应的 ThreadPoolExecutor 实例
     */
    public static ThreadPoolExecutor getPool(String poolName,
                                             int corePoolSize,
                                             int maximumPoolSize,
                                             long keepAliveSeconds,
                                             int queueCapacity,
                                             RejectedExecutionHandler handler) {
        Objects.requireNonNull(poolName, "poolName must not be null");
        Objects.requireNonNull(handler, "handler must not be null");

        return POOL_MAP.computeIfAbsent(poolName, name -> {
            log.info("Creating thread pool: name={}, core={}, max={}, keepAlive={}s, queue={}",
                    name, corePoolSize, maximumPoolSize, keepAliveSeconds, queueCapacity);

            ThreadPoolExecutor executor = new ThreadPoolExecutor(
                    corePoolSize,
                    maximumPoolSize,
                    keepAliveSeconds,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(queueCapacity),
                    new DaemonThreadFactory(name + "-task-"),
                    handler
            );
            // 重写 execute 以自动透传 MDC，与 MdcForkJoinPool 保持一致
            return new MdcThreadPoolExecutor(executor);
        });
    }

    /**
     * 优雅关闭所有已注册的线程池。
     * <p>建议在应用 ShutdownHook 或 Spring {@code @PreDestroy} 中调用。</p>
     */
    public static void shutdownAll() {
        POOL_MAP.forEach((name, pool) -> {
            log.info("Shutting down thread pool: {}", name);
            pool.shutdown();
        });
    }

    // ==================== 内部 MDC 代理 Executor ====================

    /**
     * 轻量代理：仅拦截 {@link #execute(Runnable)} 注入 MDC，其余操作委托给原始 Executor。
     * <p>继承 ThreadPoolExecutor 是为了保持类型兼容（调用方可能依赖 TPE 特有方法如 setCorePoolSize）。</p>
     */
    private static final class MdcThreadPoolExecutor extends ThreadPoolExecutor {

        private final ThreadPoolExecutor delegate;

        MdcThreadPoolExecutor(ThreadPoolExecutor delegate) {
            // 构造参数仅为满足父类要求，实际不使用
            super(0, 1, 0L, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command, "command must not be null");
            delegate.execute(new RunnableMdcWrapper(command));
        }

        // ---- 以下全部委托，保持 ThreadPoolExecutor API 完整可用 ----

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }

        @Override
        public int getCorePoolSize() {
            return delegate.getCorePoolSize();
        }

        @Override
        public void setCorePoolSize(int corePoolSize) {
            delegate.setCorePoolSize(corePoolSize);
        }

        @Override
        public int getMaximumPoolSize() {
            return delegate.getMaximumPoolSize();
        }

        @Override
        public void setMaximumPoolSize(int maximumPoolSize) {
            delegate.setMaximumPoolSize(maximumPoolSize);
        }

        @Override
        public long getKeepAliveTime(TimeUnit unit) {
            return delegate.getKeepAliveTime(unit);
        }

        @Override
        public void setKeepAliveTime(long time, TimeUnit unit) {
            delegate.setKeepAliveTime(time, unit);
        }

        @Override
        public int getPoolSize() {
            return delegate.getPoolSize();
        }

        @Override
        public int getActiveCount() {
            return delegate.getActiveCount();
        }

        @Override
        public BlockingQueue<Runnable> getQueue() {
            return delegate.getQueue();
        }

        @Override
        public long getCompletedTaskCount() {
            return delegate.getCompletedTaskCount();
        }

        @Override
        public long getTaskCount() {
            return delegate.getTaskCount();
        }

        @Override
        public RejectedExecutionHandler getRejectedExecutionHandler() {
            return delegate.getRejectedExecutionHandler();
        }

        @Override
        public void setRejectedExecutionHandler(RejectedExecutionHandler handler) {
            delegate.setRejectedExecutionHandler(handler);
        }

        @Override
        public ThreadFactory getThreadFactory() {
            return delegate.getThreadFactory();
        }

        @Override
        public void setThreadFactory(ThreadFactory threadFactory) {
            delegate.setThreadFactory(threadFactory);
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }

    // ==================== 默认拒绝策略 ====================

    /**
     * 队列满时记录警告日志并抛出 {@link java.util.concurrent.RejectedExecutionException}。
     * <p>日志中包含 poolName 和任务信息，便于定位溢出来源。</p>
     */
    public static final class DefaultAbortPolicy implements RejectedExecutionHandler {

        private final String poolName;

        public DefaultAbortPolicy(String poolName) {
            this.poolName = Objects.requireNonNull(poolName, "poolName must not be null");
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            // 不再 instanceof 检查具体 Wrapper 类型，统一打印任务描述
            String taskDesc = (r != null) ? r.toString() : "null";
            log.warn("[{}][队列溢出] rejected task={}, executor=[core={}, active={}, queue={}, completed={}]",
                    poolName, taskDesc,
                    e.getCorePoolSize(), e.getActiveCount(),
                    e.getQueue().size(), e.getCompletedTaskCount());

            throw new RejectedExecutionException("Task " + taskDesc + " rejected from " + poolName);
        }
    }
}