package com.misky.ddss.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据源切换注解
 *
 * <p>标注在 Mapper 接口（推荐）或 Service 方法上，AOP 切面自动切换到指定数据源。</p>
 *
 * <h3>用法示例</h3>
 * <pre>
 * // 推荐：放在 Mapper 接口上，整个接口的所有方法使用同一个数据源
 * {@literal @}DataSource("order-db")
 * public interface OrderMapper {
 *     List&lt;Order&gt; selectAll();
 *     Order selectById(Long id);
 * }
 *
 * // 也可以放在 Service 方法上，做更精细的切换
 * {@literal @}DataSource("report-db")
 * public List&lt;Report&gt; queryReport() { ... }
 * </pre>
 *
 * <h3>优先级</h3>
 * 方法级注解 &gt; 类级注解 &gt; 默认主库
 *
 * <h3>数据源标识</h3>
 * {@code value} 的值必须与 YAML 中数据源 key 完全一致，见
 * {@code DynamicDataSourceProperties#getDatasources()}
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DataSource {

    /**
     * 数据源标识，与 YAML 中 {@code dp.datasource.datasources.*} 的 key 对应
     */
    String value() default "";
}
