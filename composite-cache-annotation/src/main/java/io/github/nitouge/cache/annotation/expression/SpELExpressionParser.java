package io.github.nitouge.cache.annotation.expression;

import io.github.nitouge.cache.annotation.exception.AnnotationExceptionFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SpEL 表达式解析器
 *
 * <p>简化的 SpEL 表达式处理，直接使用 Spring 的 SpEL 支持。
 *
 * <h3>支持的表达式</h3>
 * <ul>
 *   <li>#参数名 - 方法参数</li>
 *   <li>#参数名.属性 - 对象属性</li>
 *   <li>#result - 方法返回值（仅 CachePut 可用）</li>
 *   <li>字符串拼接 - 'prefix:' + #id</li>
 * </ul>
 *
 */
public class SpELExpressionParser {

    /**
     * Spring SpEL 解析器
     */
    private final ExpressionParser parser = new SpelExpressionParser();

    /**
     * 参数名发现器
     */
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    /**
     * 表达式缓存
     */
    private final Map<String, Expression> expressionCache = new ConcurrentHashMap<>(256);

    /**
     * 解析 SpEL 表达式
     *
     * @param expressionString SpEL 表达式字符串
     * @param method 方法对象
     * @param args 方法参数
     * @param target 目标对象
     * @param result 方法返回值（可选）
     * @param annotationType 注解类型（用于异常提示）
     * @return 解析结果
     */
    public Object parseExpression(String expressionString, Method method, Object[] args,
                                  Object target, Object result, String annotationType) {
        try {
            // 获取或创建 Expression 对象
            Expression expression = expressionCache.computeIfAbsent(
                expressionString,
                expr -> parser.parseExpression(expr)
            );

            // 创建评估上下文
            EvaluationContext context = createEvaluationContext(method, args, target, result);

            // 评估表达式
            return expression.getValue(context);

        } catch (Exception e) {
            String methodSignature = method.getDeclaringClass().getSimpleName() + "." + method.getName();
            throw AnnotationExceptionFactory.spelParseError(
                annotationType,
                methodSignature,
                expressionString,
                e
            );
        }
    }

    /**
     * 创建评估上下文
     */
    private EvaluationContext createEvaluationContext(Method method, Object[] args,
                                                     Object target, Object result) {
        // 使用 Spring 的 MethodBasedEvaluationContext
        StandardEvaluationContext context = new StandardEvaluationContext();
        
        // 设置根对象
        context.setRootObject(target);
        
        // 设置方法参数
        String[] parameterNames = parameterNameDiscoverer.getParameterNames(method);
        if (parameterNames != null && args != null) {
            for (int i = 0; i < parameterNames.length; i++) {
                context.setVariable(parameterNames[i], args[i]);
            }
        }
        
        // 设置返回值（用于@CachePut）
        if (result != null) {
            context.setVariable("result", result);
        }
        
        return context;
    }
    
    /**
     * 清空表达式缓存
     */
    public void clearCache() {
        expressionCache.clear();
    }
    
    /**
     * 获取缓存大小
     */
    public int getCacheSize() {
        return expressionCache.size();
    }
}
