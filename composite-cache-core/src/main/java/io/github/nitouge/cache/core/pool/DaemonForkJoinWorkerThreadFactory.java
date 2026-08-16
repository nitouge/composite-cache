package io.github.nitouge.cache.core.pool;

import io.github.nitouge.cache.core.consts.PoolConsts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 创建守护模式 {@link ForkJoinWorkerThread} 的工厂，支持自定义线程名前缀。
 *
 * <p>生成的线程具有以下特征：</p>
 * <ul>
 *   <li>守护线程（不阻止 JVM 退出）</li>
 *   <li>固定优先级 {@link Thread#NORM_PRIORITY}</li>
 *   <li>线程名格式：{@code {prefix}-worker-{seq}}，序号从 1 开始单调递增</li>
 *   <li>统一的 UncaughtExceptionHandler，确保异常不被静默吞掉</li>
 * </ul>
 */
public final class DaemonForkJoinWorkerThreadFactory
        implements ForkJoinPool.ForkJoinWorkerThreadFactory {

    private static final Logger log = LoggerFactory.getLogger(DaemonForkJoinWorkerThreadFactory.class);

    private final String namePrefix;
    private final AtomicInteger counter = new AtomicInteger(0);

    /** 使用默认线程名前缀 */
    public DaemonForkJoinWorkerThreadFactory() {
        this(PoolConsts.DEFAULT_THREAD_NAME_PREFIX);
    }

    /**
     * @param threadNamePrefix 线程名前缀，不可为 null 或空白
     * @throws NullPointerException     如果 threadNamePrefix 为 null
     * @throws IllegalArgumentException 如果 threadNamePrefix 去除空白后为空
     */
    public DaemonForkJoinWorkerThreadFactory(String threadNamePrefix) {
        Objects.requireNonNull(threadNamePrefix, "threadNamePrefix must not be null");
        String trimmed = threadNamePrefix.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }
        this.namePrefix = trimmed;
    }

    @Override
    public ForkJoinWorkerThread newThread(ForkJoinPool pool) {
        int seq = counter.incrementAndGet();
        String threadName = namePrefix + "-worker-" + seq;

        if (log.isDebugEnabled()) {
            log.debug("Created ForkJoinWorkerThread: name={}, pool={}", threadName, pool);
        }

        return new DaemonForkJoinWorkerThread(pool, threadName);
    }
}