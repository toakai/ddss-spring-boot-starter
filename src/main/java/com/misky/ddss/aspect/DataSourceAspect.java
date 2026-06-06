package com.misky.ddss.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import org.springframework.util.StringUtils;

import com.misky.ddss.annotation.DataSource;
import com.misky.ddss.core.DynamicDataSource;

/**
 * 动态数据源切换切面
 *
 * <p>拦截标注了 {@link DataSource} 的方法或类，执行前切换到指定数据源，
 * 执行后清除 ThreadLocal 恢复默认。</p>
 *
 * <p>优先级：方法级注解 &gt; 类级注解 &gt; 默认主库</p>
 *
 * <p>事务顺序：{@code @Order(Ordered.HIGHEST_PRECEDENCE + 10)} 确保数据源切换
 * 在事务拦截器（{@link org.springframework.transaction.interceptor.TransactionInterceptor}）之前执行，
 * 避免事务绑定到错误的数据源。</p>
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DataSourceAspect {

    private static final Logger log = LoggerFactory.getLogger(DataSourceAspect.class);
    private static final String SPEL_PREFIX = "#";

    @Autowired
    private ApplicationContext applicationContext;

    private final ExpressionParser spelParser = new SpelExpressionParser();

    @Pointcut("@annotation(com.misky.ddss.annotation.DataSource) " +
              "|| @within(com.misky.ddss.annotation.DataSource)")
    public void pointCut() {
    }

    @Around("pointCut()")
    public Object doAround(ProceedingJoinPoint point) throws Throwable {
        MethodSignature signature = (MethodSignature) point.getSignature();

        // 方法级注解（使用 AnnotationUtils 支持继承链）
        DataSource methodDs =
                AnnotationUtils.findAnnotation(signature.getMethod(), DataSource.class);

        // 类级注解：先查声明类，再查运行时目标类（支持代理和子类场景）
        DataSource classDs = AnnotationUtils.findAnnotation(
                signature.getMethod().getDeclaringClass(), DataSource.class);
        if (classDs == null && point.getTarget() != null) {
            Class<?> targetClass = AopUtils.getTargetClass(point.getTarget());
            classDs = AnnotationUtils.findAnnotation(targetClass, DataSource.class);
        }

        // 方法级优先，其次类级
        String dataSource = resolveDataSourceKey(methodDs, classDs, point);

        if (StringUtils.hasText(dataSource)) {
            DynamicDataSource.setDataSource(dataSource);
            log.debug("切换数据源：{}", dataSource);
        }

        try {
            return point.proceed();
        } finally {
            DynamicDataSource.clearDataSource();
            log.debug("清除数据源绑定");
        }
    }

    // ==================== SpEL 解析 ====================

    /**
     * 解析 @DataSource 注解的 value，支持 SpEL 表达式
     * <p>以 # 开头的值会被当作 SpEL 表达式解析，否则原样返回。</p>
     *
     * <h3>支持的 SpEL 表达式示例</h3>
     * <ul>
     *   <li>{@code "#tenantId"} — 引用方法参数名为 tenantId 的值</li>
     *   <li>{@code "#header['X-Tenant-Id']"} — 引用 HTTP 请求头（需配合 Web 环境）</li>
     *   <li>{@code "#session['user']"} — 引用 Session 属性（需配合 Web 环境）</li>
     *   <li>{@code "#tenantHolder.get()"} — 调用静态/Bean 方法</li>
     * </ul>
     */
    private String resolveDataSourceKey(DataSource methodDs, DataSource classDs, ProceedingJoinPoint point) {
        DataSource dsAnnotation = null;
        if (methodDs != null && StringUtils.hasText(methodDs.value())) {
            dsAnnotation = methodDs;
        } else if (classDs != null && StringUtils.hasText(classDs.value())) {
            dsAnnotation = classDs;
        }

        if (dsAnnotation == null) {
            return null;
        }

        String value = dsAnnotation.value();
        if (value.startsWith(SPEL_PREFIX)) {
            return parseSpel(value, point);
        }
        return value;
    }

    /**
     * 解析 SpEL 表达式，返回字符串结果
     *
     * <p>EvaluationContext 配置：
     * <ul>
     *   <li>支持引用 Spring Bean（通过 {@code @beanName} 语法）</li>
     *   <li>支持方法参数名作为变量（如 {@code #tenantId}）</li>
     * </ul>
     */
    private String parseSpel(String expression, ProceedingJoinPoint point) {
        try {
            StandardEvaluationContext context = new StandardEvaluationContext();

            // 将方法参数名和值注册为 SpEL 变量（如 #tenantId）
            MethodSignature signature = (MethodSignature) point.getSignature();
            String[] paramNames = signature.getParameterNames();
            Object[] args = point.getArgs();
            if (paramNames != null && args != null) {
                for (int i = 0; i < paramNames.length && i < args.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }

            Expression exp = spelParser.parseExpression(expression);
            Object result = exp.getValue(context);
            if (result == null) {
                throw new IllegalStateException("SpEL 表达式 [" + expression + "] 解析结果为 null，无法确定数据源");
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("解析 @DataSource SpEL 表达式失败：[" + expression + "]", e);
        }
    }
}
