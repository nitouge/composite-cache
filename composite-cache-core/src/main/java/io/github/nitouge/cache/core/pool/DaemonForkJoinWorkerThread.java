package io.github.nitouge.cache.core.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;

/**
 * 守护模式的 {@link ForkJoinWorkerThread}，由 {@link DaemonForkJoinWorkerThreadFactory} 创建。
 *
 * <p>特性：</p>
 * <ul>
 *   <li>守护线程 + NORM_PRIORITY</li>
 *   <li>统一 UncaughtExceptionHandler 兜底异常日志</li>
 *   <li>终止时清理 MDC 上下文（防止线程复用导致上下文泄漏）</li>
 * </ul>
 */
final class DaemonForkJoinWorkerThread extends ForkJoinWorkerThread {

    private static final Logger log = LoggerFactory.getLogger(DaemonForkJoinWorkerThread.class);

    DaemonForkJoinWorkerThread(ForkJoinPool pool, String threadName) {
        super(pool);
        setName(threadName);
        setDaemon(true);
        setPriority(Thread.NORM_PRIORITY);
        setUncaughtExceptionHandler(UncaughtExceptionLogger.INSTANCE);
    }

    /**
     * 线程终止时的清理钩子。
     * <p>主要职责：清除可能残留的 MDC 上下文，防止线程被 ForkJoinPool 复用时携带脏数据。</p>
     */
    @Override
    protected void onTermination(Throwable exception) {
        // 先清理 MDC，再调用父类逻辑
        try {
            org.slf4j.MDC.clear();
        } catch (Throwable ignored) {
            // MDC.clear() 不应抛出异常，但防御性处理避免掩盖真正的 termination 逻辑
        }

        super.onTermination(exception);

        if (exception != null && log.isWarnEnabled()) {
            log.warn("ForkJoinWorkerThread terminated with exception: name={}", getName(), exception);
        }
    }

    // ==================== 内部异常处理器 ====================

    /**
     * 将未捕获异常以 ERROR 级别记录到 SLF4J。
     * <p>ForkJoinWorkerThread 的异常不会传播到提交方，必须在此兜底。</p>
     */
    private static final class UncaughtExceptionLogger
            implements Thread.UncaughtExceptionHandler {

        static final UncaughtExceptionLogger INSTANCE = new UncaughtExceptionLogger();

        private static final Logger log = LoggerFactory.getLogger(UncaughtExceptionLogger.class);

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            log.error("Uncaught exception in ForkJoinWorkerThread [{}]: {}",
                    t.getName(), e.getMessage(), e);
        }
    }
}