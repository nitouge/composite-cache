package io.github.nitouge.cache.core.sync;

import io.github.nitouge.cache.core.consts.enums.CacheSyncTypeEnum;
import io.github.nitouge.cache.core.sync.listener.CacheSyncMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EnhancedRedisCacheSyncPolicy} 中版本检查（乱序保护）逻辑的测试。
 * 门控被拆分为只读的 {@code isStaleVersion} 检查和成功后的 {@code commitVersion} 写入
 * （两者均为纯逻辑，无需 Redis），此处通过反射调用。
 *
 */
public class EnhancedRedisCacheSyncPolicyVersionTest {

    private EnhancedRedisCacheSyncPolicy policy;
    private Method isStaleVersion;
    private Method commitVersion;

    @BeforeEach
    public void setUp() throws Exception {
        policy = new EnhancedRedisCacheSyncPolicy();
        isStaleVersion = EnhancedRedisCacheSyncPolicy.class
                .getDeclaredMethod("isStaleVersion", CacheSyncMessage.class);
        isStaleVersion.setAccessible(true);
        commitVersion = EnhancedRedisCacheSyncPolicy.class
                .getDeclaredMethod("commitVersion", CacheSyncMessage.class);
        commitVersion.setAccessible(true);
    }

    private CacheSyncMessage msg(String cacheName, Object key, long version) {
        return new CacheSyncMessage()
                .setCacheName(cacheName)
                .setKey(key)
                .setCacheSyncType(CacheSyncTypeEnum.EVICT)
                .setVersion(version);
    }

    private boolean stale(String cacheName, Object key, long version) throws Exception {
        return (boolean) isStaleVersion.invoke(policy, msg(cacheName, key, version));
    }

    /** 模拟 onMessage 的两阶段流程：非 stale 才处理并提交版本；返回是否被处理。 */
    private boolean process(String cacheName, Object key, long version) throws Exception {
        CacheSyncMessage m = msg(cacheName, key, version);
        if ((boolean) isStaleVersion.invoke(policy, m)) {
            return false;
        }
        commitVersion.invoke(policy, m);
        return true;
    }

    @Test
    public void firstMessage_shouldBeProcessed() throws Exception {
        assertThat(process("user", 1L, 100L)).isTrue();
    }

    @Test
    public void olderVersion_shouldBeStale() throws Exception {
        assertThat(process("user", 1L, 100L)).isTrue();
        assertThat(process("user", 1L, 90L)).isFalse();   // 迟到的旧消息
    }

    @Test
    public void newerVersion_shouldBeProcessed() throws Exception {
        assertThat(process("user", 1L, 100L)).isTrue();
        assertThat(process("user", 1L, 110L)).isTrue();  // 更新的消息
    }

    @Test
    public void equalVersion_shouldBeStale() throws Exception {
        assertThat(process("user", 1L, 100L)).isTrue();
        assertThat(process("user", 1L, 100L)).isFalse();   // 相同版本视为重复
    }

    @Test
    public void differentKeys_shouldBeIndependent() throws Exception {
        assertThat(process("user", 1L, 100L)).isTrue();
        assertThat(process("user", 2L, 50L)).isTrue();   // 不同 key 各自独立计版本
    }

    @Test
    public void sameKeyDifferentCache_shouldBeIndependent() throws Exception {
        assertThat(process("user", 1L, 100L)).isTrue();
        assertThat(process("order", 1L, 50L)).isTrue();  // 不同 cacheName 各自独立
    }

    @Test
    public void zeroVersion_shouldNotBeGated() throws Exception {
        // version<=0 表示未设置版本号，跳过门控以保持兼容
        assertThat(process("user", 1L, 0L)).isTrue();
        assertThat(process("user", 1L, 0L)).isTrue();
    }

    @Test
    public void nullKey_shouldNotBeGated() throws Exception {
        // 全局操作（如 CLEAR）不做版本门控
        assertThat(process("user", null, 100L)).isTrue();
        assertThat(process("user", null, 50L)).isTrue();
    }

    /**
     * C8-1 回归：{@code isStaleVersion} 是<b>只读</b>判断，单独调用不提交版本号。
     * 模拟"监听器处理失败 → 未 commit"：同版本的重发/补偿消息仍能被处理，不会被误判为 stale，
     * 从而避免处理失败后 L1 永久脏数据。
     */
    @Test
    public void isStaleVersionIsReadOnly_failedProcessingDoesNotGateResend() throws Exception {
        // 多次只读判断本身不应记录版本号
        assertThat(stale("user", 1L, 100L)).isFalse();
        assertThat(stale("user", 1L, 100L)).isFalse();
        // 模拟一次处理失败（未提交版本）→ 重发同版本仍可处理
        assertThat(process("user", 1L, 100L)).isTrue();
        // 处理成功并提交版本后，再来的同版本才视为 stale
        assertThat(process("user", 1L, 100L)).isFalse();
    }
}
