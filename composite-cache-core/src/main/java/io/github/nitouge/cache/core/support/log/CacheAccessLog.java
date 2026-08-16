package io.github.nitouge.cache.core.support.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 缓存访问链路日志（命中来源追踪）。
 *
 * <p>使用<b>独立 logger 名</b> {@code io.github.nitouge.cache.access}，与各实现类自身的 logger 分离——
 * 想观察"数据到底来自 L1 / L2 / 回源(DB)"的链路时，单独开启它即可，不会被其它 debug 日志淹没：
 * <pre>logging.level.io.github.nitouge.cache.access=DEBUG</pre>
 *
 * <p>每次缓存读取只输出<b>一条决定性来源</b>（L1 命中 / L2 命中 / 回源加载 / 未命中），即该次访问的链路结论，
 * 做到"精准而非冗多"。
 *
 * <h3>性能</h3>
 * <ul>
 *   <li>所有方法内部先 {@code isDebugEnabled()} 判断；关闭时（默认）仅一次布尔检查，无字符串拼接、无装箱开销。</li>
 *   <li>本类为 DEBUG 级，<b>默认关闭</b>，对生产路径零影响。</li>
 * </ul>
 *
 * <h3>安全</h3>
 * <ul>
 *   <li>只输出 {@code cacheName} 与 {@code key}，<b>绝不输出缓存值</b>，避免敏感数据进入日志。</li>
 *   <li>若 key 本身可能含敏感信息，请勿开启本 logger（或自行对 key 脱敏）。</li>
 * </ul>
 *
 */
public final class CacheAccessLog {

    private static final Logger log = LoggerFactory.getLogger("io.github.nitouge.cache.access");

    private CacheAccessLog() {
    }

    /**
     * 链路日志是否开启。供调用方在需要时跳过额外准备工作（一般无需，直接调用对应方法即可）。
     */
    public static boolean isEnabled() {
        return log.isDebugEnabled();
    }

    /**
     * 命中 L1（本地缓存）。
     */
    public static void hitL1(String cacheName, Object key) {
        if (log.isDebugEnabled()) {
            log.debug("[cache:{}] key={} <- L1(本地) 命中", cacheName, key);
        }
    }

    /**
     * 命中 L2（远程缓存），并回填 L1。
     */
    public static void hitL2(String cacheName, Object key) {
        if (log.isDebugEnabled()) {
            log.debug("[cache:{}] key={} <- L2(远程) 命中，回填 L1", cacheName, key);
        }
    }

    /**
     * L2 未命中，按回源策略加载（数据源/DB），随后回填 L2、L1。
     *
     * @param loadStrategy 回源策略（NONE/LOCK/LOGICAL_EXPIRE）
     */
    public static void loadFromSource(String cacheName, Object key, Object loadStrategy) {
        if (log.isDebugEnabled()) {
            log.debug("[cache:{}] key={} <- 回源加载(数据源)：L2 未命中，策略={}，将回填 L2/L1", cacheName, key, loadStrategy);
        }
    }

    /**
     * 无 L2（仅 L1 模式 / 未配置 L2）时直达数据源加载。
     */
    public static void loadFromSourceNoL2(String cacheName, Object key) {
        if (log.isDebugEnabled()) {
            log.debug("[cache:{}] key={} <- 回源加载(数据源)：无 L2，直达数据源", cacheName, key);
        }
    }

    /**
     * L1 与 L2 均未命中，且无回源（纯查询，如 getIfPresent / 不带 loader 的 get）。
     */
    public static void miss(String cacheName, Object key) {
        if (log.isDebugEnabled()) {
            log.debug("[cache:{}] key={} x 未命中(L1+L2)", cacheName, key);
        }
    }
}
