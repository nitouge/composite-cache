package io.github.nitouge.cache.core.util;

import org.slf4j.MDC;

import java.util.Map;

/**
 * MDC (Mapped Diagnostic Context) 工具类。
 * <p>
 * 主要用于在异步执行场景（如线程池、CompletableFuture）中安全地传递和恢复日志上下文，
 * 防止因线程复用导致的 MDC 污染或丢失。
 * </p>
 *
 * <pre>{@code
 * // 使用示例
 * Map<String, String> contextMap = MDC.getCopyOfContextMap();
 * executor.execute(() -> {
 *     Map<String, String> oldContext = MdcUtils.beforeExecution(contextMap);
 *     try {
 *         // 业务逻辑...
 *     } finally {
 *         MdcUtils.afterExecution(oldContext);
 *     }
 * });
 * }</pre>
 */
public final class MdcUtils {

    private MdcUtils() {
        // 工具类禁止实例化
    }

    /**
     * 在执行异步任务前调用，将指定的 MDC 上下文设置到当前线程。
     * <p>
     * 注意：必须在 finally 块中配合 {@link #afterExecution(Map)} 使用，
     * 否则会导致线程池中的 MDC 上下文泄漏。
     * </p>
     *
     * @param newMdcContext 需要传递的 MDC 上下文快照（可为 null，表示清空当前上下文）
     * @return 当前线程原有的 MDC 上下文快照，用于后续恢复
     */
    public static Map<String, String> beforeExecution(Map<String, String> newMdcContext) {
        // 先保存旧上下文（getCopyOfContextMap 在线程无 MDC 时可能返回 null）
        Map<String, String> oldMdcContext = MDC.getCopyOfContextMap();

        if (newMdcContext == null || newMdcContext.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(newMdcContext);
        }

        return oldMdcContext;
    }

    /**
     * 在执行异步任务后调用，恢复当前线程之前的 MDC 上下文。
     * <p>
     * 此方法应在 finally 块中调用，确保即使业务抛出异常也能正确恢复上下文。
     * </p>
     *
     * @param oldMdcContext 由 {@link #beforeExecution(Map)} 返回的旧上下文快照
     */
    public static void afterExecution(Map<String, String> oldMdcContext) {
        if (oldMdcContext == null || oldMdcContext.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(oldMdcContext);
        }
    }

}