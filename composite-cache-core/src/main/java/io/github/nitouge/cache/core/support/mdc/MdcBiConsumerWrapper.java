package io.github.nitouge.cache.core.support.mdc;

import io.github.nitouge.cache.core.util.MdcUtils;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 传播 MDC（链路上下文）的 {@link BiConsumer} 包装器，用于异步回调场景。
 *
 * <p>构造时（提交线程）捕获当前 MDC 快照，{@link #accept} 执行（回调线程）前
 * 将快照还原到当前线程，执行后恢复原有 MDC，确保异步回调中的日志仍携带
 * 正确的 traceId 等链路信息，且不污染回调线程原上下文。</p>
 *
 * <pre>{@code
 * // 典型用法：CompletableFuture.whenComplete
 * CompletableFuture<String> future = asyncService.query();
 * future.whenComplete(new MdcBiConsumerWrapper<>((result, ex) -> {
 *     if (ex != null) {
 *         log.error("查询失败", ex);   // ← 日志中自动携带 traceId
 *     } else {
 *         log.info("查询结果: {}", result);
 *     }
 * }));
 * }</pre>
 *
 * @param <T> 回调的第一个参数类型（如 CompletableFuture 的结果类型）
 * @see MdcUtils
 * @see RunnableMdcWrapper
 */
public class MdcBiConsumerWrapper<T> implements BiConsumer<T, Throwable> {

    /** 被包装的原始回调（不可为 null） */
    private final BiConsumer<T, Throwable> delegate;

    /** 构造时捕获的 MDC 上下文快照（不可变） */
    private final Map<String, String> contextSnapshot;

    /**
     * @param action 被包装的回调，不可为 null
     * @throws NullPointerException 如果 action 为 null
     */
    public MdcBiConsumerWrapper(BiConsumer<T, Throwable> action) {
        this.delegate = Objects.requireNonNull(action, "action must not be null");

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        this.contextSnapshot = (mdc != null && !mdc.isEmpty())
                ? Collections.unmodifiableMap(mdc)
                : Collections.emptyMap();
    }

    @Override
    public void accept(T result, Throwable throwable) {
        Map<String, String> oldContext = MdcUtils.beforeExecution(contextSnapshot);
        try {
            delegate.accept(result, throwable);
        } finally {
            MdcUtils.afterExecution(oldContext);
        }
    }
}