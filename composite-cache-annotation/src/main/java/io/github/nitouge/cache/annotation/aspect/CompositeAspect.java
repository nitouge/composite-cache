package io.github.nitouge.cache.annotation.aspect;

import io.github.nitouge.cache.annotation.CacheAble;
import io.github.nitouge.cache.annotation.CacheEvict;
import io.github.nitouge.cache.annotation.CachePut;
import io.github.nitouge.cache.annotation.Cache_L1;
import io.github.nitouge.cache.annotation.Cache_L2;
import io.github.nitouge.cache.annotation.Caches;
import io.github.nitouge.cache.annotation.exception.AnnotationExceptionFactory;
import io.github.nitouge.cache.annotation.validation.AnnotationConfigValidator;
import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L1CacheSetting;
import io.github.nitouge.cache.core.config.setting.L2CacheSetting;
import io.github.nitouge.cache.core.consts.enums.CacheModeEnum;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;

/**
 * 组合缓存切面处理器
 * 
 * <p>处理 @CacheAble、@CachePut、@CacheEvict、@Caches 注解的 AOP 切面。
 * 
 * <h3>核心功能</h3>
 * <ul>
 *   <li>@CacheAble - 缓存查询，命中则不执行方法</li>
 *   <li>@CachePut - 缓存更新，总是执行方法并更新缓存</li>
 *   <li>@CacheEvict - 缓存删除，支持单个删除或清空</li>
 *   <li>@Caches - 组合注解，支持同时使用多个缓存注解</li>
 * </ul>
 * 
 * <h3>技术特性</h3>
 * <ul>
 *   <li>统一异常处理 - 使用 AnnotationExceptionFactory 创建异常</li>
 *   <li>配置验证 - 使用 AnnotationConfigValidator 验证注解配置</li>
 *   <li>监控统计 - 可选的性能监控支持</li>
 *   <li>动态缓存创建 - 缓存不存在时自动创建</li>
 *   <li>容错降级 - 支持 ignoreException 配置</li>
 * </ul>
 * 
 * <h3>执行流程</h3>
 * <pre>
 * {@code @CacheAble:}
 *   1. 验证注解配置
 *   2. 生成缓存 Key
 *   3. 查询缓存（命中则返回，未命中则执行方法）
 *   4. 记录监控指标
 *
 * {@code @CachePut:}
 *   1. 执行方法
 *   2. 生成缓存 Key
 *   3. 更新缓存
 *   4. 返回方法结果
 * 
 * {@code @CacheEvict:}
 *   1. 删除缓存（单个或全部）
 *   2. 执行方法
 *   3. 返回方法结果
 * </pre>
 * 
 */
@Slf4j
@Aspect
public class CompositeAspect extends AbstractCacheAspect {
    
    @Pointcut("@annotation(io.github.nitouge.cache.annotation.CacheAble)")
    public void cacheAblePointcut() {
    }
    
    @Pointcut("@annotation(io.github.nitouge.cache.annotation.CacheEvict)")
    public void cacheEvictPointcut() {
    }
    
    @Pointcut("@annotation(io.github.nitouge.cache.annotation.CachePut)")
    public void cachePutPointcut() {
    }
    
    @Pointcut("@annotation(io.github.nitouge.cache.annotation.Caches)")
    public void cachesPointcut() {
    }
    
    /**
     * 处理 @CacheAble 注解
     * 
     * <p>执行流程：
     * <ol>
     *   <li>验证注解配置</li>
     *   <li>生成缓存 Key</li>
     *   <li>使用 cache.get(key, callable) 查询缓存</li>
     *   <li>命中返回缓存值，未命中执行方法并缓存结果</li>
     *   <li>记录监控指标（命中率、耗时等）</li>
     * </ol>
     * 
     * @param joinPoint AOP 切点
     * @return 缓存值或方法执行结果
     * @throws Throwable 方法执行异常或缓存操作异常
     */
    @Around("cacheAblePointcut()")
    public Object handleCacheAble(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.nanoTime();
        Method method = getMethod(joinPoint);
        String methodSignature = getShortMethodSignature(joinPoint);
        CacheAble annotation = AnnotationUtils.findAnnotation(method, CacheAble.class);
        
        if (annotation == null) {
            return joinPoint.proceed();
        }
        
        // 验证注解配置
        AnnotationConfigValidator.validate(annotation, methodSignature);

        // 记录"用户方法是否在加载回调里自己抛了异常"，用于区分两类失败：
        //  - 用户方法自身抛错 → 原样抛出，不重试、不当作缓存故障（否则会重复执行方法、产生重复副作用）；
        //  - 缓存基础设施失败（key 解析/L2 访问等）→ 才走 ignoreException 降级到执行一次方法。
        final Throwable[] methodError = new Throwable[1];
        try {
            // 生成缓存 Key：keyExpr 优先，否则回退 类名:方法名:参数
            Object key = resolveKey(annotation.keyExpr(), method, joinPoint.getArgs(), joinPoint.getTarget(), null, "@CacheAble");

            if (key == null) {
                throw AnnotationExceptionFactory.cacheKeyNull("@CacheAble", methodSignature, key);
            }

            // 获取或创建缓存
            Cache cache = getOrCreateCache(annotation.cacheName(), annotation);

            // 直接使用 cache.get(key, callable) 方法
            // 命中/未命中由 CompositeCache 统一记录（避免与注解层重复计数），此处不再记录
            Object result = cache.get(key, () -> {
                try {
                    log.debug("[@CacheAble] Cache miss, loading from method: cacheName={}, key={}, method={}", annotation.cacheName(), key, methodSignature);
                    return joinPoint.proceed();
                } catch (Throwable e) {
                    methodError[0] = e; // 标记：用户方法自身抛错
                    if (e instanceof RuntimeException) {
                        throw (RuntimeException) e;
                    }
                    if (e instanceof Error) {
                        throw (Error) e;
                    }
                    throw new RuntimeException("Failed to execute method", e);
                }
            });

            log.debug("[@CacheAble] Result obtained: cacheName={}, key={}, method={}", annotation.cacheName(), key, methodSignature);
            return result;

        } catch (Exception e) {
            // 用户方法自身抛错：原样抛出（不重试、不计为缓存异常、不走 ignoreException）
            if (methodError[0] != null) {
                throw methodError[0];
            }
            // 仅缓存基础设施异常才降级
            recordException(annotation.cacheName());
            if (annotation.ignoreException()) {
                log.warn("[@CacheAble] Cache infrastructure failed, fallback to method execution: method={}", methodSignature, e);
                return joinPoint.proceed();
            }
            throw AnnotationExceptionFactory.cacheOperationFailed("@CacheAble", methodSignature, "get", e);
        } finally {
            recordLatency(annotation.cacheName(), System.nanoTime() - startTime);
        }
    }
    
    /**
     * 处理 @CachePut 注解
     * 
     * <p>执行流程：
     * <ol>
     *   <li>执行方法（不查询缓存）</li>
     *   <li>生成缓存 Key</li>
     *   <li>将方法返回值写入缓存</li>
     *   <li>返回方法结果</li>
     * </ol>
     * 
     * <p>注意：总是执行方法，不管缓存是否存在。
     * 
     * @param joinPoint AOP 切点
     * @return 方法执行结果
     * @throws Throwable 方法执行异常或缓存操作异常
     */
    @Around("cachePutPointcut()")
    public Object handleCachePut(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.nanoTime();
        Method method = getMethod(joinPoint);
        String methodSignature = getShortMethodSignature(joinPoint);
        CachePut annotation = AnnotationUtils.findAnnotation(method, CachePut.class);
        
        if (annotation == null) {
            return joinPoint.proceed();
        }
        
        try {
            // 执行方法
            Object result = joinPoint.proceed();

            // 生成缓存 Key：keyExpr 优先（可引用 #result），否则回退 类名:方法名:参数
            Object key = resolveKey(annotation.keyExpr(), method, joinPoint.getArgs(), joinPoint.getTarget(), result, "@CachePut");

            if (key == null) {
                throw AnnotationExceptionFactory.cacheKeyNull("@CachePut", methodSignature, key);
            }

            // 获取或创建缓存（缺失时按注解配置创建，避免空指针）
            Cache cache = getOrCreateCache(annotation.cacheName(), annotation.cacheMode(), annotation.cacheL1(), annotation.cacheL2());
            if (cache == null) {
                log.warn("[@CachePut] Cache unavailable, skip put: cacheName={}, method={}", annotation.cacheName(), methodSignature);
                return result;
            }

            // 更新缓存
            cache.put(key, result);
            log.debug("[@CachePut] Cache updated: cacheName={}, key={}, method={}", annotation.cacheName(), key, methodSignature);
            return result;
            
        } catch (Exception e) {
            recordException(annotation.cacheName());
            throw AnnotationExceptionFactory.cacheOperationFailed("@CachePut", methodSignature, "put", e);
        } finally {
            recordLatency(annotation.cacheName(), System.nanoTime() - startTime);
        }
    }
    
    /**
     * 处理 @CacheEvict 注解
     * 
     * <p>执行流程：
     * <ol>
     *   <li>根据 removeAll 判断删除模式</li>
     *   <li>removeAll=true: 清空整个缓存空间</li>
     *   <li>removeAll=false: 删除指定 Key 的缓存</li>
     *   <li>执行方法</li>
     *   <li>返回方法结果</li>
     * </ol>
     * 
     * @param joinPoint AOP 切点
     * @return 方法执行结果
     * @throws Throwable 方法执行异常或缓存操作异常
     */
    @Around("cacheEvictPointcut()")
    public Object handleCacheEvict(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.nanoTime();
        Method method = getMethod(joinPoint);
        String methodSignature = getShortMethodSignature(joinPoint);
        CacheEvict annotation = AnnotationUtils.findAnnotation(method, CacheEvict.class);
        
        if (annotation == null) {
            return joinPoint.proceed();
        }
        
        try {
            // 删除场景：缓存不存在则无可删除，不为了删除而动态创建缓存（避免顺带建出 L2 连接）。
            // 与 @Caches 中的 handleSingleEvict 保持一致（都用 getCache，不创建）。
            Cache cache = getCache(annotation.cacheName());
            if (cache == null) {
                log.debug("[@CacheEvict] Cache not present, nothing to evict: cacheName={}, method={}", annotation.cacheName(), methodSignature);
                return joinPoint.proceed();
            }

            // 删除缓存
            if (annotation.removeAll()) {
                // 清空所有缓存
                cache.clear();
                log.debug("[@CacheEvict] Cache cleared: cacheName={}, method={}", annotation.cacheName(), methodSignature);
            } else {
                // 生成缓存 Key：keyExpr 优先，否则回退 类名:方法名:参数
                Object key = resolveKey(annotation.keyExpr(), method, joinPoint.getArgs(), joinPoint.getTarget(), null, "@CacheEvict");

                if (key == null) {
                    throw AnnotationExceptionFactory.cacheKeyNull("@CacheEvict", methodSignature, key);
                }
                cache.evict(key);
                log.debug("[@CacheEvict] Cache evicted: cacheName={}, key={}, method={}", annotation.cacheName(), key, methodSignature);
            }
            
            // 执行方法
            return joinPoint.proceed();
            
        } catch (Exception e) {
            recordException(annotation.cacheName());
            throw AnnotationExceptionFactory.cacheOperationFailed("@CacheEvict", methodSignature, "evict", e);
        } finally {
            recordLatency(annotation.cacheName(), System.nanoTime() - startTime);
        }
    }
    
    /**
     * 处理 @Caches 注解（组合注解）
     * 
     * <p>执行顺序：
     * <ol>
     *   <li>执行所有 @CacheEvict 注解</li>
     *   <li>执行所有 @CachePut 注解</li>
     *   <li>执行所有 @CacheAble 注解</li>
     * </ol>
     * 
     * <p>注意：方法只执行一次，多个注解共享方法结果。
     * 
     * @param joinPoint AOP 切点
     * @return 方法执行结果
     * @throws Throwable 方法执行异常或缓存操作异常
     */
    @Around("cachesPointcut()")
    public Object handleCaches(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = getMethod(joinPoint);
        Caches annotation = AnnotationUtils.findAnnotation(method, Caches.class);
        
        if (annotation == null) {
            return joinPoint.proceed();
        }
        
        // @Caches 语义：目标方法只执行一次，结果在各子注解间共享。
        // 注意：与单独的 @CacheAble（read-through，命中则不执行方法）不同，在 @Caches 中方法总会执行
        //（因为常与 Evict/Put 混用），故此处 @CacheAble 按"把方法结果写入其 key"处理（等同 put）。
        // 这样修复了旧实现的两个缺陷：含 Evict/Put 时 @CacheAble 永不写缓存、以及多个 @CacheAble 只处理第一个。
        Object result = joinPoint.proceed();

        Object[] args = joinPoint.getArgs();
        Object target = joinPoint.getTarget();

        // 1.先处理@CacheEvict（清理旧值）
        for (CacheEvict evict : annotation.cacheEvict()) {
            handleSingleEvict(evict, method, args, target, result);
        }

        // 2.再处理@CachePut（写入新值）
        for (CachePut put : annotation.cachePut()) {
            handleSinglePut(put, method, args, target, result);
        }

        // 3.最后处理 @CacheAble（把方法结果写入其 key）
        for (CacheAble able : annotation.cacheAble()) {
            handleSingleCacheAblePut(able, method, args, target, result);
        }

        return result;
    }

    /**
     * 处理单个 @CacheAble（用于 @Caches 组合注解）：把已执行的方法结果写入其 key。
     *
     * <p>在 @Caches 中方法已执行一次，故此处不做 read-through，仅做缓存写入（等同 put），
     * 保证每个 @CacheAble 都生效、且与 Evict/Put 混用时不被跳过。
     *
     * @param annotation CacheAble 注解
     * @param method 方法对象
     * @param args 方法参数
     * @param target 目标对象
     * @param result 方法返回值
     */
    private void handleSingleCacheAblePut(CacheAble annotation, Method method, Object[] args, Object target, Object result) {
        String methodSignature = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        AnnotationConfigValidator.validate(annotation, methodSignature);

        // 生成缓存 Key：keyExpr 优先（@Caches 内方法已执行，可引用 #result），否则回退 类名:方法名:参数
        Object key = resolveKey(annotation.keyExpr(), method, args, target, result, "@CacheAble");
        if (key == null) {
            throw AnnotationExceptionFactory.cacheKeyNull("@CacheAble", methodSignature, key);
        }

        Cache cache = getOrCreateCache(annotation.cacheName(), annotation);
        if (cache != null) {
            cache.put(key, result);
        }
    }

    /**
     * 处理单个 @CachePut（用于 @Caches 组合注解）
     *
     * @param annotation CachePut 注解
     * @param method 方法对象
     * @param args 方法参数
     * @param target 目标对象
     * @param result 方法返回值
     */
    private void handleSinglePut(CachePut annotation, Method method, Object[] args, Object target, Object result) {
        String methodSignature = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        AnnotationConfigValidator.validate(annotation, methodSignature);

        // 生成缓存 Key：keyExpr 优先（可引用 #result），否则回退 类名:方法名:参数
        Object key = resolveKey(annotation.keyExpr(), method, args, target, result, "@CachePut");
        if (key == null) {
            throw AnnotationExceptionFactory.cacheKeyNull("@CachePut", methodSignature, key);
        }

        Cache cache = getOrCreateCache(annotation.cacheName(), annotation.cacheMode(), annotation.cacheL1(), annotation.cacheL2());
        if (cache != null) {
            cache.put(key, result);
        }
    }
    
    /**
     * 处理单个 @CacheEvict（用于 @Caches 组合注解）
     *
     * @param annotation CacheEvict 注解
     * @param method 方法对象
     * @param args 方法参数
     * @param target 目标对象
     * @param result 方法返回值
     */
    private void handleSingleEvict(CacheEvict annotation, Method method, Object[] args, Object target, Object result) {
        String methodSignature = method.getDeclaringClass().getSimpleName() + "." + method.getName();
        AnnotationConfigValidator.validate(annotation, methodSignature);
        
        Cache cache = getCache(annotation.cacheName());
        if (cache == null) {
            return;
        }
        
        if (annotation.removeAll()) {
            cache.clear();
        } else {
            // 生成缓存 Key：keyExpr 优先，否则回退 类名:方法名:参数
            Object key = resolveKey(annotation.keyExpr(), method, args, target, null, "@CacheEvict");
            if (key == null) {
                throw AnnotationExceptionFactory.cacheKeyNull("@CacheEvict", methodSignature, key);
            }
            cache.evict(key);
        }
    }
    
    /**
     * 获取或创建缓存
     * 
     * <p>如果缓存不存在，根据注解配置动态创建缓存实例。
     * 
     * @param cacheName 缓存名称
     * @param annotation CacheAble 注解（可为 null）
     * @return 缓存实例
     */
    private Cache getOrCreateCache(String cacheName, CacheAble annotation) {
        if (annotation == null) {
            return getCache(cacheName);
        }
        return getOrCreateCache(cacheName, annotation.cacheMode(), annotation.cacheL1(), annotation.cacheL2());
    }

    /**
     * 获取或创建缓存（通用版，供 @CachePut/@CacheEvict 复用，避免缓存缺失时空指针）。
     *
     * @param cacheName 缓存名称
     * @param cacheMode 缓存模式
     * @param l1        L1 配置（可为 null，使用默认）
     * @param l2        L2 配置（可为 null，使用默认）
     */
    private Cache getOrCreateCache(String cacheName, CacheModeEnum cacheMode, Cache_L1 l1, Cache_L2 l2) {
        Cache cache = getCache(cacheName);
        if (cache == null) {
            CacheSetting setting = buildCacheSetting(cacheMode, l1, l2);
            cache = cacheManager.getMissingCache(cacheName, setting);
        }
        return cache;
    }
    
    /**
     * 构建缓存设置
     * 
     * <p>根据 @CacheAble 注解配置构建 CacheSetting 对象。
     * 
     * <p>支持的模式：
     * <ul>
     *   <li>L1 模式：只配置 L1（本地缓存）</li>
     *   <li>L2 模式：只配置 L2（Redis 缓存）</li>
     *   <li>L1_L2 模式：同时配置 L1 和 L2（默认）</li>
     * </ul>
     * 
     * <p>配置优先级：
     * <ol>
     *   <li>优先使用注解中的 cacheMode 配置</li>
     *   <li>如果未配置 cacheMode，根据 cacheL1 和 cacheL2 的配置推断</li>
     *   <li>如果都未配置，使用默认 L1_L2 模式</li>
     * </ol>
     * 
     * @param annotation CacheAble 注解
     * @return CacheSetting 配置对象
     */
    private CacheSetting buildCacheSetting(CacheModeEnum cacheMode, Cache_L1 l1, Cache_L2 l2) {
        CacheSetting setting = new CacheSetting();

        // L1 配置
        boolean hasL1Config = l1 != null;
        if (hasL1Config) {
            L1CacheSetting l1Setting = new L1CacheSetting();
            l1Setting.setInitialCapacity(l1.initialCapacity());
            l1Setting.setMaximumSize(l1.maximumSize());
            l1Setting.setExpireTime((long) l1.TTL());
            l1Setting.setExpireTimeUnit(l1.timeUnit());
            // 过期模式：WRITE（写后过期，默认）/ ACCESS（访问后过期），透传到 L1 provider 的 expireAfter* 策略
            l1Setting.setCacheExpireModeEnum(l1.expireMode());
            setting.setL1CacheSetting(l1Setting);
        }

        // L2 配置
        boolean hasL2Config = l2 != null;
        if (hasL2Config) {
            L2CacheSetting l2Setting = new L2CacheSetting();
            l2Setting.setExpireTime((long) l2.TTL());
            l2Setting.setExpireTimeUnit(l2.timeUnit());
            setting.setL2CacheSetting(l2Setting);
        }

        // 优先使用注解中的 cacheMode 配置
        if (cacheMode != null) {
            setting.setCacheModeEnum(cacheMode);

            // 如果 cacheMode 是 L1_L2 或 L1，但没有配置 L1，则使用默认 L1 配置
            if ((cacheMode == CacheModeEnum.L1 || cacheMode == CacheModeEnum.L1_L2) && !hasL1Config) {
                L1CacheSetting defaultL1 = new L1CacheSetting();
                defaultL1.setInitialCapacity(10);
                defaultL1.setMaximumSize(1000);
                defaultL1.setExpireTime(60L);
                defaultL1.setExpireTimeUnit(java.util.concurrent.TimeUnit.SECONDS);
                setting.setL1CacheSetting(defaultL1);
            }

            // 如果 cacheMode 是 L1_L2 或 L2，但没有配置 L2，则使用默认 L2 配置
            if ((cacheMode == CacheModeEnum.L2 || cacheMode == CacheModeEnum.L1_L2) && !hasL2Config) {
                L2CacheSetting defaultL2 = new L2CacheSetting();
                defaultL2.setExpireTime(300L);
                defaultL2.setExpireTimeUnit(java.util.concurrent.TimeUnit.SECONDS);
                setting.setL2CacheSetting(defaultL2);
            }
        } else if (hasL1Config && hasL2Config) {
            setting.setCacheModeEnum(CacheModeEnum.L1_L2);
        } else if (hasL1Config) {
            setting.setCacheModeEnum(CacheModeEnum.L1);
        } else if (hasL2Config) {
            setting.setCacheModeEnum(CacheModeEnum.L2);
        } else {
            setting.setCacheModeEnum(CacheModeEnum.L1_L2);
        }

        return setting;
    }
}
