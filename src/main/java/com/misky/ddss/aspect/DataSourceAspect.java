package com.misky.ddss.aspect;

import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;

import com.misky.ddss.annotation.DataSource;
import com.misky.ddss.core.DynamicDataSource;

/**
 * 动态数据源切换切面
 *
 * <p>拦截标注了 {@link DataSource} 的方法或类，执行前切换到指定数据源，
 * 执行后清除 ThreadLocal 恢复默认。</p>
 *
 * <p>优先级：方法级注解 &gt; 类级注解 &gt; 默认主库</p>
 */
@Aspect
@Order(-1)
public class DataSourceAspect {

    private static final Logger log = LoggerFactory.getLogger(DataSourceAspect.class);

    @Pointcut("@annotation(com.misky.ddss.annotation.DataSource) " +
              "|| @within(com.misky.ddss.annotation.DataSource)")
    public void pointCut() {
    }

    @Around("pointCut()")
    public Object doAround(ProceedingJoinPoint point) throws Throwable {
        MethodSignature signature = (MethodSignature) point.getSignature();
        DataSource methodDs = signature.getMethod().getAnnotation(DataSource.class);
        DataSource classDs = signature.getMethod().getDeclaringClass().getAnnotation(DataSource.class);

        // 方法级优先，其次类级
        String dataSource = null;
        if (methodDs != null && StringUtils.isNotBlank(methodDs.value())) {
            dataSource = methodDs.value();
        } else if (classDs != null && StringUtils.isNotBlank(classDs.value())) {
            dataSource = classDs.value();
        }

        if (StringUtils.isNotBlank(dataSource)) {
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
}
