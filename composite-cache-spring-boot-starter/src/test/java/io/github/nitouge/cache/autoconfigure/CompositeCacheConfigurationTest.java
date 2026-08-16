package io.github.nitouge.cache.autoconfigure;

import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.core.api.CacheTemplate;
import io.github.nitouge.cache.core.metrics.CacheMetricsRecorder;
import io.github.nitouge.cache.core.consistency.CacheDeleteCompensation;
import io.github.nitouge.cache.core.consistency.MasterSlaveConsistencyHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 使用 {@link ApplicationContextRunner} 对 {@link CompositeCacheConfiguration} 进行端到端冒烟测试。
 * 验证 starter 在 L1-only 模式（无 Redis）下正确装配，以及可选的一致性 bean 仅在相应属性启用时注册。
 *
 */
public class CompositeCacheConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CompositeCacheConfiguration.class));

    @Test
    public void whenDisabled_shouldNotCreateBeans() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(CacheManager.class));
    }

    @Test
    public void whenEnabledL1Only_shouldWireCoreBeans() {
        runner.withPropertyValues(
                        "composite-cache.enabled=true",
                        "composite-cache.config.cache-mode=L1")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(CacheManager.class);
                    assertThat(ctx).hasSingleBean(CacheTemplate.class);
                    // 统一指标记录器始终存在（无监控后端时为 NoOp）
                    assertThat(ctx).hasSingleBean(CacheMetricsRecorder.class);
                    // 一致性能力默认关闭
                    assertThat(ctx).doesNotHaveBean(CacheDeleteCompensation.class);
                    assertThat(ctx).doesNotHaveBean(MasterSlaveConsistencyHandler.class);
                });
    }

    @Test
    public void whenConsistencyEnabled_shouldRegisterOptInBeans() {
        runner.withPropertyValues(
                        "composite-cache.enabled=true",
                        "composite-cache.config.cache-mode=L1",
                        "composite-cache.config.consistency.delete-compensation=true",
                        "composite-cache.config.consistency.master-slave=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(CacheDeleteCompensation.class);
                    assertThat(ctx).hasSingleBean(MasterSlaveConsistencyHandler.class);
                });
    }

    @Test
    public void whenDelayedDoubleDeleteEnabled_contextShouldStartCleanly() {
        runner.withPropertyValues(
                        "composite-cache.enabled=true",
                        "composite-cache.config.cache-mode=L1",
                        "composite-cache.config.consistency.delayed-double-delete=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(CacheManager.class);
                });
    }
}
