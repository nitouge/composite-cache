package io.github.nitouge.cache.annotation.aspect;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 {@link AbstractCacheAspect#resolveKey} 在 keyExpr（SpEL）有值时优先使用它，
 * 在 keyExpr 为空时回退到 类名:方法名:参数 策略。
 *
 * <p>核心意义在于证明 {@code @CacheAble(keyExpr="#id")} 和使用原始 id 的批量查找
 * 解析出相同的 key，使读取/写入/删除三者保持一致。
 *
 */
public class CacheKeyResolutionTest {

    /** 最小化的具体切面实现；resolveKey 只需要 spelParser 和 keyGenerator（字段初始化）。 */
    private final CompositeAspect aspect = new CompositeAspect();

    /** 示例目标类，其方法提供 SpEL 所需的参数名。 */
    static class UserService {
        public Object getUserById(Long id) { return null; }
        public Object updateUser(User user) { return null; }
    }

    static class User {
        private final Long id;
        User(Long id) { this.id = id; }
        public Long getId() { return id; }
    }

    private Method method(String name, Class<?>... params) throws Exception {
        return UserService.class.getMethod(name, params);
    }

    @Test
    public void keyExpr_simpleParam_resolvesToRawValue() throws Exception {
        Method m = method("getUserById", Long.class);
        Object key = aspect.resolveKey("#id", m, new Object[]{1L}, new UserService(), null, "@CacheAble");
        // #id -> 1L（与批量使用原始 id 的 key 一致），证明单注解与批量可对齐
        assertThat(key).isEqualTo(1L);
    }

    @Test
    public void keyExpr_objectProperty_resolvesToProperty() throws Exception {
        Method m = method("updateUser", User.class);
        Object key = aspect.resolveKey("#user.id", m, new Object[]{new User(42L)}, new UserService(), null, "@CachePut");
        assertThat(key).isEqualTo(42L);
    }

    @Test
    public void keyExpr_stringConcat_resolvesToString() throws Exception {
        Method m = method("getUserById", Long.class);
        Object key = aspect.resolveKey("'user:' + #id", m, new Object[]{7L}, new UserService(), null, "@CacheAble");
        assertThat(key).isEqualTo("user:7");
    }

    @Test
    public void keyExpr_result_resolvesFromReturnValue() throws Exception {
        Method m = method("updateUser", User.class);
        Object key = aspect.resolveKey("#result.id", m, new Object[]{new User(1L)}, new UserService(), new User(99L), "@CachePut");
        assertThat(key).isEqualTo(99L);
    }

    @Test
    public void emptyKeyExpr_fallsBackToClassMethodParams() throws Exception {
        Method m = method("getUserById", Long.class);
        Object key = aspect.resolveKey("", m, new Object[]{1L}, new UserService(), null, "@CacheAble");
        // 回退策略：ClassName:methodName:param（CustomCacheKeyGenerator）
        assertThat(key).isEqualTo("UserService:getUserById:1");
    }

    @Test
    public void singleAndBatchKey_align_whenUsingIdKeyExpr() throws Exception {
        Method m = method("getUserById", Long.class);
        // 单注解 keyExpr="#id"
        Object single = aspect.resolveKey("#id", m, new Object[]{5L}, new UserService(), null, "@CacheAble");
        // 批量场景里业务 key 就是原始 id
        Object batch = 5L;
        assertThat(single).isEqualTo(batch);
    }
}
