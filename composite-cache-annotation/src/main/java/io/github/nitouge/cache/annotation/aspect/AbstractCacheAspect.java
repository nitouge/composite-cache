package io.github.nitouge.cache.annotation.aspect;

import io.github.nitouge.cache.annotation.expression.SpELExpressionParser;
import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.api.CacheManager;
import io.github.nitouge.cache.annotation.key.CacheKeyGenerator;
import io.github.nitouge.cache.annotation.key.CustomCacheKeyGenerator;
import io.github.nitouge.cache.core.metrics.CacheMetricsRecorder;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * 缓存切面基类
 * 
 * <p>提取公共逻辑，为所有缓存切面提供基础功能支持。
 * 
 * <h3>核心功能</h3>
 * <ul>
 *   <li>SpEL 表达式解析 - 统一的表达式解析逻辑</li>
 *   <li>Key 生成 - 支持多种 Key 生成策略</li>
 *   <li>缓存获取 - 统一的缓存实例获取</li>
 *   <li>监控统计 - 可选的性能监控支持</li>
 * </ul>
 * 
 * <h3>子类实现</h3>
 * <ul>
 *   <li>{@link CompositeAspect} - 处理@CacheAble、@CachePut、@CacheEvict</li>
 *   <li>{@link BatchCacheAspect} - 处理@BatchCacheAble</li>
 * </ul>
 * 
 */
@Slf4j
public abstract class AbstractCacheAspect {
    
    /**
     * SpEL 表达式解析器
     *
     * <p>用于解析注解中的 SpEL 表达式，支持：
     * <ul>
     *   <li>#参数名 - 方法参数</li>
     *   <li>#参数名.属性 - 对象属性</li>
     *   <li>#result - 方法返回值</li>
     *   <li>字符串拼接等复杂表达式</li>
     * </ul>
     */
    protected final SpELExpressionParser spelParser = new SpELExpressionParser();

    /**
     * 缓存管理器
     *
     * <p>负责管理所有缓存实例，提供缓存的创建、获取、销毁等功能。
     */
    @Autowired
    protected CacheManager cacheManager;

    /**
     * Key 生成器
     *
     * <p>用于生成缓存 Key，默认使用 CustomCacheKeyGenerator。
     * <p>可通过 Spring 配置自定义 Key 生成器。
     */
    @Autowired(required = false)
    protected CacheKeyGenerator keyGenerator = new CustomCacheKeyGenerator();
    
    /**
     * 统一指标记录器（可选）
     *
     * <p>与核心层共用同一套 {@link CacheMetricsRecorder} 口径。命中/未命中由 CompositeCache 统一记录，
     * 切面仅记录批量命中/未命中（核心层批量路径未记录）、注解耗时与异常，避免跨层重复计数。
     */
    @Autowired(required = false)
    protected CacheMetricsRecorder metricsRecorder;
    
    /**
     * 获取方法签名（完整路径）
     * 
     * <p>格式：包名.类名.方法名
     * <p>示例：com.example.service.UserService.getUserById
     * 
     * @param joinPoint 切点
     * @return 完整方法签名
     */
    protected String getMethodSignature(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getDeclaringTypeName() + "." + signature.getName();
    }
    
    /**
     * 获取简短方法签名（不含包名）
     * 
     * <p>格式：类名.方法名
     * <p>示例：UserService.getUserById
     * 
     * @param joinPoint 切点
     * @return 简短方法签名
     */
    protected String getShortMethodSignature(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getDeclaringType().getSimpleName() + "." + signature.getName();
    }
    
    /**
     * 获取 Method 对象
     *
     * @param joinPoint 切点
     * @return Method 对象
     */
    protected Method getMethod(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        return signature.getMethod();
    }

    /**
     * 解析 SpEL 表达式
     *
     * <p>支持的表达式：
     * <ul>
     *   <li>#id - 参数名</li>
     *   <li>#user.id - 对象属性</li>
     *   <li>#result - 方法返回值</li>
     *   <li>'prefix:' + #id - 字符串拼接</li>
     * </ul>
     *
     * @param expression SpEL 表达式字符串
     * @param method 方法对象
     * @param args 方法参数数组
     * @param target 目标对象（this）
     * @param result 方法返回值（可选，仅 @CachePut 可用）
     * @param annotationType 注解类型（用于异常提示）
     * @return 表达式解析结果
     */
    protected Object parseSpEL(String expression, Method method, Object[] args,
                              Object target, Object result, String annotationType) {
        return spelParser.parseExpression(expression, method, args, target, result, annotationType);
    }

    /**
     * 获取缓存实例
     *
     * <p>从缓存管理器中获取指定名称的缓存。
     * <p>如果缓存不存在，返回 null 并记录警告日志。
     *
     * @param cacheName 缓存名称
     * @return 缓存实例，不存在则返回 null
     */
    protected Cache getCache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            log.warn("Cache not found: {}", cacheName);
        }
        return cache;
    }

    /**
     * 生成缓存 Key（简化版）
     *
     * <p>用于 SpEL 表达式解析后的 Key 值处理。
     * <p>如果配置了 KeyGenerator，则使用 KeyGenerator 进行二次处理；
     * <p>否则直接返回 keyValue。
     *
     * <p>使用场景：配合 keyExpr 使用
     *
     * @param keyValue SpEL 表达式解析后的值
     * @return 最终的缓存 Key
     */
    protected Object generateKey(Object keyValue) {
        if (keyGenerator != null) {
            return keyGenerator.generate(keyValue);
        }
        return keyValue;
    }
    
    /**
     * 解析缓存 Key（注解统一入口）。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code keyExpr} 非空：用 SpEL 解析（支持 {@code #参数}、{@code #对象.属性}、{@code #result}、字符串拼接），
     *       再经 {@link CacheKeyGenerator#generate(Object)} 做简化处理（集合/数组/Map 规整，普通值原样返回）。
     *       这样 {@code keyExpr="#id"} 生成的 key 与编程式/批量使用的业务 key 一致，
     *       从而 {@code @CacheAble}/{@code @CachePut}/{@code @CacheEvict} 能对齐同一条缓存项。</li>
     *   <li>{@code keyExpr} 为空：回退到 {@code 类名:方法名:参数} 的完整 key 策略。</li>
     * </ul>
     *
     * @param keyExpr        SpEL 表达式（可为空）
     * @param method         方法对象
     * @param args           方法参数
     * @param target         目标对象（this）
     * @param result         方法返回值（@CachePut 可引用 #result，其余传 null）
     * @param annotationType 注解类型（异常提示用）
     * @return 缓存 key；SpEL 解析结果为 null 时返回 null（由调用方按需报错）
     */
    protected Object resolveKey(String keyExpr, Method method, Object[] args,
                                Object target, Object result, String annotationType) {
        if (StringUtils.hasText(keyExpr)) {
            Object keyValue = parseSpEL(keyExpr, method, args, target, result, annotationType);
            if (keyValue == null) {
                return null;
            }
            return generateKey(keyValue);
        }
        return generateKey(target, method, args);
    }

    /**
     * 生成缓存 Key（完整版）
     *
     * <p>使用 KeyGenerator 生成包含类名、方法名和参数的完整 Key。
     * <p>格式（CUSTOM 模式）：ClassName:methodName:param1:param2
     *
     * <p>使用场景：未配置 keyExpr 时使用
     *
     * @param target 目标对象（this）
     * @param method 方法对象
     * @param params 方法参数数组
     * @return 最终的缓存 Key
     */
    protected Object generateKey(Object target, Method method, Object... params) {
        if (keyGenerator != null) {
            return keyGenerator.generate(target, method, params);
        }
        // 降级：如果没有 KeyGenerator，使用简单策略
        if (params == null || params.length == 0) {
            return method.getName();
        }
        if (params.length == 1) {
            return params[0];
        }
        return java.util.Arrays.toString(params);
    }
    
    /**
     * 记录注解层操作耗时（包含注解切面开销）
     *
     * @param cacheName 缓存名称
     * @param nanos     耗时（纳秒）
     */
    protected void recordLatency(String cacheName, long nanos) {
        if (metricsRecorder != null) {
            metricsRecorder.recordLatency(cacheName, "annotation", nanos, TimeUnit.NANOSECONDS);
        }
    }

    /**
     * 记录注解层操作异常
     */
    protected void recordException(String cacheName) {
        if (metricsRecorder != null) {
            metricsRecorder.recordException(cacheName, "annotation");
        }
    }
}
