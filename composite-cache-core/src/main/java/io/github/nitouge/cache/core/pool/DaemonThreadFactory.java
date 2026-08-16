package io.github.nitouge.cache.core.pool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 创建守护线程的 {@link ThreadFactory}，适用于后台任务、定时调度、异步回调等场景。
 *
 * <p>生成的线程具有以下特征：</p>
 * <ul>
 *   <li>守护线程（不阻止 JVM 退出）</li>
 *   <li>固定优先级 {@link Thread#NORM_PRIORITY}</li>
 *   <li>线程名格式：{@code {prefix}-{seq}}，序号从 1 开始递增</li>
 *   <li>统一的 UncaughtExceptionHandler，确保异常不被静默吞掉</li>
 * </ul>
 */
public class DaemonThreadFactory implements ThreadFactory {

    private static final Logger log = LoggerFactory.getLogger(DaemonThreadFactory.class);

    private final String namePrefix;
    private final AtomicInteger counter = new AtomicInteger(1);

    /**
     * @param threadNamePrefix 线程名前缀，不可为 null；最终线程名为 "{prefix}-{n}"
     * @throws NullPointerException 如果 threadNamePrefix 为 null
     */
    public DaemonThreadFactory(String threadNamePrefix) {
        this.namePrefix = Objects.requireNonNull(threadNamePrefix, "threadNamePrefix must not be null");
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, namePrefix + "-" + counter.getAndIncrement());
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY);
        thread.setUncaughtExceptionHandler(DefaultUncaughtExceptionHandler.INSTANCE);
        return thread;
    }

    // ==================== 内部默认异常处理器 ====================

    /**
     * 将未捕获异常以 ERROR 级别记录到 SLF4J，避免被 JVM 默认处理器静默丢弃。
     * <p>使用单例避免每个线程持有独立的 handler 实例。</p>
     */
    private static final class DefaultUncaughtExceptionHandler
            implements Thread.UncaughtExceptionHandler {

        static final DefaultUncaughtExceptionHandler INSTANCE = new DefaultUncaughtExceptionHandler();

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            log.error("Uncaught exception in thread [{}]: {}", t.getName(), e.getMessage(), e);
        }
    }
}