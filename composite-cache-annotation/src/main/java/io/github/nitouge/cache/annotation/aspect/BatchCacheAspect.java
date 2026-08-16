package io.github.nitouge.cache.annotation.aspect;

import io.github.nitouge.cache.annotation.BatchCacheAble;
import io.github.nitouge.cache.annotation.Cache_L1;
import io.github.nitouge.cache.annotation.Cache_L2;
import io.github.nitouge.cache.annotation.exception.AnnotationExceptionFactory;
import io.github.nitouge.cache.annotation.exception.CacheAnnotationException;
import io.github.nitouge.cache.annotation.validation.AnnotationConfigValidator;
import io.github.nitouge.cache.core.api.Cache;
import io.github.nitouge.cache.core.config.setting.CacheSetting;
import io.github.nitouge.cache.core.config.setting.L1CacheSetting;
import io.github.nitouge.cache.core.config.setting.L2CacheSetting;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.util.StringUtils;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 重构后的批量缓存切面处理器
 * 
 * <p>拦截 @BatchCacheAble 注解，实现批量缓存查询和回填。
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>批量查询缓存 - 一次性查询多个 Key</li>
 *   <li>智能回源 - 只查询未命中的数据</li>
 *   <li>批量回填 - 一次性写入多个缓存</li>
 *   <li>顺序保持 - 使用 LinkedHashMap 保持顺序</li>
 * </ul>
 *
 * <h3>执行流程</h3>
 * <pre>
 * 输入：[1, 2, 3, 4, 5]
 *
 * 1. 批量查询缓存
 *    命中：{1: user1, 3: user3}
 *    未命中：[2, 4, 5]
 *
 * 2. 只查询未命中的 ID
 *    调用方法：getUserByIds([2, 4, 5])
 *    返回：[user2, user4, user5]
 *
 * 3. 批量写入缓存
 *    写入：{2: user2, 4: user4, 5: user5}
 *
 * 4. 合并结果并按原始顺序返回
 *    返回：[user1, user2, user3, user4, user5]
 * </pre>
 *
 * <h3>性能优化</h3>
 * <ul>
 *   <li>批量操作 - 减少网络往返次数</li>
 *   <li>智能回源 - 只查询必要的数据</li>
 *   <li>顺序保持 - 使用 LinkedHashMap 保持顺序</li>
 * </ul>
 *
 */
@Slf4j
@Aspect
public class BatchCacheAspect extends AbstractCacheAspect {
    
    @Pointcut("@annotation(io.github.nitouge.cache.annotation.BatchCacheAble)")
    public void batchCacheAblePointcut() {
    }
    
    /**
     * 处理 @BatchCacheAble 注解
     */
    @Around("batchCacheAblePointcut()")
    public Object handleBatchCacheAble(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.nanoTime();
        Method method = getMethod(joinPoint);
        String methodSignature = getShortMethodSignature(joinPoint);
        BatchCacheAble annotation = method.getAnnotation(BatchCacheAble.class);
        
        if (annotation == null) {
            return joinPoint.proceed();
        }
        
        // 验证注解配置
        AnnotationConfigValidator.validate(annotation, methodSignature);
        
        try {
            // 1.获取输入的 ID 列表
            List<?> inputIds = extractInputIds(joinPoint.getArgs(), annotation.inputParam());
            if (inputIds == null || inputIds.isEmpty()) {
                log.debug("[@BatchCacheAble] Input ids is empty, return empty result: method={}", methodSignature);
                // 按方法声明的返回类型返回空结果（List/Set/数组），避免固定返回 emptyList 与签名不符
                return adaptReturnType(Collections.emptyList(), method.getReturnType());
            }
            
            log.debug("[@BatchCacheAble] Start batch cache: method={}, inputSize={}", methodSignature, inputIds.size());

            // 2.获取或创建缓存
            Cache cache = getOrCreateCache(annotation.cacheName(), annotation);

            // 3.委托核心 batchGetOrLoad：内部统一完成 L1→L2→仅对未命中回源→回填 L1/L2→空值缓存(防穿透)，
            //   命中/未命中指标也由核心层统一记录，避免切面重复实现且能力更弱（旧实现不缓存空值，无法防穿透）。
            Map<Object, Object> keyMap = buildKeyMap(inputIds);
            Map<Object, Object> resultMap = cache.batchGetOrLoad(
                    keyMap,
                    missedKeys -> loadFromMethod(joinPoint, missedKeys, annotation.keyExtractor(), method),
                    annotation.returnNullValueKey());

            // 4.按原始顺序返回结果（过滤掉 NullValue 对应的 null），并适配方法声明的返回类型
            List<Object> finalResults = convertToList(resultMap, inputIds);
            log.debug("[@BatchCacheAble] Complete: method={}, resultSize={}", methodSignature, finalResults.size());

            return adaptReturnType(finalResults, method.getReturnType());

        } catch (Exception e) {
            recordException(annotation.cacheName());

            if (annotation.ignoreException()) {
                log.warn("[@BatchCacheAble] Batch cache operation failed, fallback to method execution: method={}", methodSignature, e);
                return joinPoint.proceed();
            }

            throw AnnotationExceptionFactory.cacheOperationFailed("@BatchCacheAble", methodSignature, "batchGet", e);
        } finally {
            recordLatency(annotation.cacheName(), System.nanoTime() - startTime);
        }
    }
    
    /**
     * 提取输入的 ID 列表
     */
    private List<?> extractInputIds(Object[] args, String inputParam) {
        if (args == null || args.length == 0) {
            return null;
        }

        // 支持通过 SpEL 表达式获取参数
        if (StringUtils.hasText(inputParam) && inputParam.startsWith("#")) {
            // TODO: 后续可以支持 SpEL 表达式
            log.warn("[@BatchCacheAble] SpEL input param not supported yet, use first parameter");
        }
        
        // 默认使用第一个参数
        Object firstArg = args[0];
        
        if (firstArg instanceof List) {
            return (List<?>) firstArg;
        } else if (firstArg instanceof Collection) {
            return new ArrayList<>((Collection<?>) firstArg);
        } else if (firstArg instanceof Object[]) {
            return Arrays.asList((Object[]) firstArg);
        } else {
            throw new CacheAnnotationException(
                "@BatchCacheAble",
                "unknown",
                String.format("Input parameter must be List/Collection/Array, but got: %s", firstArg.getClass().getName())
            );
        }
    }
    
    /**
     * 构建 Key 映射（ID -> ID）
     */
    private Map<Object, Object> buildKeyMap(List<?> inputIds) {
        return inputIds.stream()
            .collect(Collectors.toMap(
                id -> id,
                id -> id,
                (v1, v2) -> v1,
                LinkedHashMap::new
            ));
    }

    /**
     * 作为核心 batchGetOrLoad 的值加载器：对未命中的 key 调用原方法回源，并按 keyExtractor 组装为 Map。
     * <p>原方法声明的受检异常在此转为运行时异常，交由核心层与外层统一处理。
     */
    private Map<Object, Object> loadFromMethod(ProceedingJoinPoint joinPoint, List<Object> missedKeys, String keyExtractor, Method method) {
        try {
            List<?> dbResults = queryDatabase(joinPoint, missedKeys);
            return extractResultMap(dbResults, keyExtractor, method, joinPoint.getArgs(), joinPoint.getTarget());
        } catch (Throwable t) {
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            }
            if (t instanceof Error) {
                throw (Error) t;
            }
            throw new RuntimeException(t);
        }
    }

    /**
     * 查询数据库（只查询未命中的 ID）
     */
    private List<?> queryDatabase(ProceedingJoinPoint joinPoint, List<Object> missedIds) throws Throwable {
        if (missedIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 修改参数为未命中的 ID 列表
        Object[] newArgs = joinPoint.getArgs().clone();
        newArgs[0] = missedIds;
        
        // 执行原方法
        Object result = joinPoint.proceed(newArgs);
        
        if (result instanceof List) {
            return (List<?>) result;
        } else if (result instanceof Collection) {
            return new ArrayList<>((Collection<?>) result);
        } else if (result == null) {
            return Collections.emptyList();
        } else {
            throw new CacheAnnotationException(
                "@BatchCacheAble",
                "unknown",
                String.format("Method return type must be List/Collection, but got: %s", result.getClass().getName())
            );
        }
    }
    
    /**
     * 从结果列表中提取 Key-Value 映射
     */
    private Map<Object, Object> extractResultMap(List<?> results, String keyExtractor, Method method, Object[] args, Object target) {
        Map<Object, Object> resultMap = new LinkedHashMap<>();
        for (Object result : results) {
            if (result == null) {
                continue;
            }

            try {
                // 使用 SpEL 表达式提取 Key
                Object key = parseSpEL(
                    keyExtractor,
                    method,
                    args,
                    result,  // 使用 result 作为 target
                    result,  // 使用 result 作为 result
                    "@BatchCacheAble"
                );
                
                if (key != null) {
                    resultMap.put(key, result);
                } else {
                    log.warn("[@BatchCacheAble] Extracted key is null, skip: result={}", result);
                }
                
            } catch (Exception e) {
                log.error("[@BatchCacheAble] Failed to extract key: result={}, keyExtractor={}", result, keyExtractor, e);
            }
        }
        
        return resultMap;
    }
    
    /**
     * 转换为 List（按原始顺序）
     */
    private List<Object> convertToList(Map<Object, Object> resultMap, List<?> originalIds) {
        return originalIds.stream()
            .map(resultMap::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }

    /**
     * 把按原始顺序组装的结果适配到方法<b>声明的返回类型</b>（List / Set / 数组），
     * 避免固定返回 List 与方法签名不符导致调用方 ClassCastException（A1-6）。
     *
     * <p>规则：数组 → 按元素类型新建数组；Set 子类型 → LinkedHashSet（保序）；
     * 其余（List/Collection/Iterable/Object）→ 直接返回有序 List。
     */
    private Object adaptReturnType(List<Object> results, Class<?> returnType) {
        if (returnType.isArray()) {
            Class<?> componentType = returnType.getComponentType();
            Object array = Array.newInstance(componentType, results.size());
            for (int i = 0; i < results.size(); i++) {
                Array.set(array, i, results.get(i));
            }
            return array;
        }
        if (Set.class.isAssignableFrom(returnType)) {
            return new LinkedHashSet<>(results);
        }
        return results;
    }
    
    /**
     * 获取或创建缓存
     */
    private Cache getOrCreateCache(String cacheName, BatchCacheAble annotation) {
        Cache cache = getCache(cacheName);
        
        // 如果缓存不存在，动态创建
        if (cache == null) {
            CacheSetting setting = buildCacheSetting(annotation);
            cache = cacheManager.getMissingCache(cacheName, setting);
            log.info("[@BatchCacheAble] Cache created dynamically: cacheName={}", cacheName);
        }
        
        return cache;
    }
    
    /**
     * 构建缓存设置
     */
    private CacheSetting buildCacheSetting(BatchCacheAble annotation) {
        CacheSetting setting = new CacheSetting();
        setting.setCacheModeEnum(annotation.cacheMode());
        
        // L1 配置
        Cache_L1 l1 = annotation.cacheL1();
        if (l1 != null) {
            L1CacheSetting l1Setting = new L1CacheSetting();
            l1Setting.setInitialCapacity(l1.initialCapacity());
            l1Setting.setMaximumSize(l1.maximumSize());
            l1Setting.setExpireTime((long) l1.TTL());
            l1Setting.setExpireTimeUnit(l1.timeUnit());
            setting.setL1CacheSetting(l1Setting);
        }

        // L2 配置
        Cache_L2 l2 = annotation.cacheL2();
        if (l2 != null) {
            L2CacheSetting l2Setting = new L2CacheSetting();
            l2Setting.setExpireTime((long) l2.TTL());
            l2Setting.setExpireTimeUnit(l2.timeUnit());
            l2Setting.setDataType(l2.dataType());
            setting.setL2CacheSetting(l2Setting);
        }
        
        return setting;
    }
}
