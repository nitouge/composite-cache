package io.github.nitouge.cache.core.support.mdc;

import io.github.nitouge.cache.core.util.MdcUtils;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * 传播 MDC（链路上下文）的 {@link Callable} 包装器，用于异步提交场景。
 *
 * <p>构造时（提交线程）捕获当前 MDC 快照，{@link #call()} 执行（工作线程）前
 * 将快照还原到当前线程，执行后恢复原有 MDC，确保异步任务中的日志仍携带
 * 正确的 traceId 等链路信息，且不污染工作线程原上下文。</p>
 *
 * <pre>{@code
 * // 典型用法：ExecutorService.submit
 * Future<String> future = executor.submit(new CallableMdcWrapper<>(() -> {
 *     log.info("异步任务执行中"); // ← 日志中自动携带 traceId
 *     return service.query();
 * }));
 * }</pre>
 *
 * @param <V> Callable 的返回值类型
 * @see MdcUtils
 * @see RunnableMdcWrapper
 * @see MdcBiConsumerWrapper
 */
public class CallableMdcWrapper<V> implements Callable<V> {

    /** 被包装的原始任务（不可为 null） */
    private final Callable<V> delegate;

    /** 构造时捕获的 MDC 上下文快照（不可变） */
    private final Map<String, String> contextSnapshot;

    /**
     * @param callable 被包装的任务，不可为 null
     * @throws NullPointerException 如果 callable 为 null
     */
    public CallableMdcWrapper(Callable<V> callable) {
        this.delegate = Objects.requireNonNull(callable, "callable must not be null");

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        this.contextSnapshot = (mdc != null && !mdc.isEmpty())
                ? Collections.unmodifiableMap(mdc)
                : Collections.emptyMap();
    }

    @Override
    public V call() throws Exception {
        Map<String, String> oldContext = MdcUtils.beforeExecution(contextSnapshot);
        try {
            return delegate.call();
        } finally {
            MdcUtils.afterExecution(oldContext);
        }
    }
}