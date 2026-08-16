package io.github.nitouge.cache.core.pool;

import io.github.nitouge.cache.core.support.mdc.CallableMdcWrapper;
import io.github.nitouge.cache.core.support.mdc.RunnableMdcWrapper;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 自定义 {@link ForkJoinPool}：在任务提交时自动透传 MDC 上下文，以便链路追踪。
 *
 * <p>本框架中的唯一用途是作为 Caffeine 的 {@code executor(Executor)}——Caffeine 只会调用
 * {@link #execute(Runnable)} 执行异步维护任务（如 removalListener 回调）。因此本类仅重写了该方法。</p>
 *
 * <h3>重要限制</h3>
 * <p>{@link #submit}, {@link #invoke}, {@link #invokeAll} 等方法<b>不会</b>自动传递 MDC。
 * 如果未来有非 Caffeine 场景需要使用这些方法，请自行包装 Runnable/Callable 或扩展本类。</p>
 *
 * <p>Worker 线程为守护线程（见 {@link DaemonForkJoinWorkerThread}），不会阻塞 JVM 退出。</p>
 */
public class MdcForkJoinPool extends ForkJoinPool {

    /** 线程池编号序列（线程安全） */
    private static final AtomicInteger POOL_SEQUENCE = new AtomicInteger(0);

    /** 默认线程名称前缀 */
    public static final String DEFAULT_THREAD_NAME_PREFIX = "mdc-fjp";

    /** 全局共享单例（守护线程），供 Caffeine executor 复用 */
    private static final MdcForkJoinPool SHARED_INSTANCE = new MdcForkJoinPool();

    /**
     * 获取全局共享的 MDC ForkJoinPool 实例。
     * <p>该实例为守护线程池，无需手动 shutdown。</p>
     */
    public static MdcForkJoinPool sharedPool() {
        return SHARED_INSTANCE;
    }

    /**
     * 使用默认并行度（CPU 核心数）和默认线程名前缀创建实例。
     */
    public MdcForkJoinPool() {
        this(Runtime.getRuntime().availableProcessors(), DEFAULT_THREAD_NAME_PREFIX);
    }

    /**
     * @param parallelism      并行度，建议 ≤ CPU 核心数
     * @param threadNamePrefix 自定义线程名前缀，最终线程名为 "{prefix}-{poolId}-worker-{n}"
     * @throws IllegalArgumentException 如果 parallelism ≤ 0
     */
    public MdcForkJoinPool(int parallelism, String threadNamePrefix) {
        super(
                parallelism,
                new DaemonForkJoinWorkerThreadFactory(threadNamePrefix + "-" + POOL_SEQUENCE.incrementAndGet()),
                null,   // 使用默认 UncaughtExceptionHandler
                false   // asyncMode=false，适合 Caffeine 的同步移除监听器场景
        );
    }

    /**
     * 透传 MDC 上下文后提交任务。
     * <p><b>这是本类唯一保证 MDC 传递的入口。</b></p>
     *
     * @param task 要执行的任务
     * @throws NullPointerException 如果 task 为 null
     */
    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        super.execute(new RunnableMdcWrapper(task));
    }


    /**
     * 透传 MDC 上下文后提交 Callable 任务。
     */
    @Override
    public <T> ForkJoinTask<T> submit(Callable<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        return super.submit(new CallableMdcWrapper<>(task));
    }

    /**
     * 透传 MDC 上下文后提交 Runnable 任务（带返回值）。
     */
    @Override
    public <T> ForkJoinTask<T> submit(Runnable task, T result) {
        Objects.requireNonNull(task, "task must not be null");
        return super.submit(new RunnableMdcWrapper(task), result);
    }

    /**
     * 透传 MDC 上下文后提交 Runnable 任务。
     */
    @Override
    public ForkJoinTask<?> submit(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        return super.submit(new RunnableMdcWrapper(task));
    }

    /**
     * 透传 MDC 上下文后批量调用。
     */
    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) {
        Objects.requireNonNull(tasks, "tasks must not be null");
        List<Callable<T>> wrapped = tasks.stream()
                .map(CallableMdcWrapper::new)
                .collect(Collectors.toList());
        return super.invokeAll(wrapped);
    }

}