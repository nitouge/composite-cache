package io.github.nitouge.cache.core.support.mdc;

import io.github.nitouge.cache.core.util.MdcUtils;
import lombok.Getter;
import org.slf4j.MDC;

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * MDC 上下文感知的 Runnable 包装器。
 * <p>
 * 在构造时捕获当前线程的 MDC 快照，在执行时自动设置并在结束后恢复，
 * 确保异步任务中日志上下文的正确传递。
 * </p>
 *
 * <pre>{@code
 * // 基本用法
 * executor.execute(new RunnableMdcWrapper(() -> log.info("async task")));
 *
 * // 携带业务参数（便于调试/监控）
 * executor.execute(new RunnableMdcWrapper(() -> process(order), order));
 * }</pre>
 */
public class RunnableMdcWrapper implements Runnable, Serializable {

    private static final long serialVersionUID = 1L;

    /** 被包装的原始任务（不可为 null） */
    private final Runnable delegate;

    /**
     *  获取构造时捕获的 MDC 上下文快照（只读）。
     */
    @Getter
    private final Map<String, String> contextSnapshot;

    /**
     *  可选的业务关联参数，仅用于调试/监控，不参与任务执行。
     */
    @Getter
    private final Object attachment;

    /**
     * @param runnable 被包装的任务，不可为 null
     */
    public RunnableMdcWrapper(Runnable runnable) {
        this(runnable, null);
    }

    /**
     * @param runnable   被包装的任务，不可为 null
     * @param attachment 可选的业务关联参数（建议为可序列化对象）
     */
    public RunnableMdcWrapper(Runnable runnable, Object attachment) {
        this.delegate = Objects.requireNonNull(runnable, "runnable must not be null");
        this.attachment = attachment;

        // 防御性拷贝 + 不可变包装，防止外部篡改 & 降低序列化风险
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        this.contextSnapshot = (mdc != null && !mdc.isEmpty())
                ? Collections.unmodifiableMap(mdc)
                : Collections.emptyMap();
    }

    @Override
    public void run() {
        Map<String, String> oldContext = MdcUtils.beforeExecution(contextSnapshot);
        try {
            delegate.run();
        } finally {
            // 确保即使 delegate 抛异常也能恢复 MDC
            MdcUtils.afterExecution(oldContext);
        }
    }

}
