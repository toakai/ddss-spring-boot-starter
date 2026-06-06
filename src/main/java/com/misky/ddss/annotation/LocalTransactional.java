package com.misky.ddss.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 本地多数据源事务注解
 *
 * <p>管理多个数据源上的事务，保证（尽最大努力）同时提交或回滚。</p>
 *
 * <h3>局限性（重要）</h3>
 * <p>本方案基于 Best-Efforts 1PC 模式，<b>无法保证真正的原子性</b>。
 * 如果第一个数据源事务提交成功，第二个提交失败，已提交的第一个事务无法回滚。
 * 对于需要强一致性的场景，请使用 Seata 等分布式事务框架。</p>
 *
 * <h3>用法示例</h3>
 * <pre>{@code
 * // 指定参与事务的数据源 key（YAML 中配置的）
 * {@literal @}LocalTransactional({"master", "report"})
 * public void saveToTwoDatabases() {
 *     masterMapper.insert(...);   // 在 master 事务中
 *     reportMapper.insert(...);    // 在 report 事务中
 * }
 *
 * // 不指定数据源 key：自动检测当前线程访问过的数据源
 * {@literal @}LocalTransactional
 * public void autoDetect() { ... }
 * }</pre>
 *
 * <h3>事务行为</h3>
 * <ul>
 *   <li>方法正常结束 → 按注册顺序依次提交所有事务</li>
 *   <li>方法抛异常 → 按注册顺序依次回滚所有事务</li>
 *   <li>某个提交/回滚失败 → 记录警告日志，继续处理其余事务</li>
 * </ul>
 *
 * <h3>与 {@code @Transactional} 的关系</h3>
 * <p>{@code @LocalTransactional} 管理的是多个独立数据源上的本地事务，
 * 每个数据源各自有一个事务。这与 Spring 原生的 {@code @Transactional}
 * （单数据源事务）不冲突，但<b>不要在同一方法上混用</b>。</p>
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface LocalTransactional {

    /**
     * 参与事务的数据源 key 列表
     * <p>对应 YAML 中 {@code dp.datasource.datasources} 下的 key。</p>
     * <p>如为空，则自动检测当前线程访问过的所有数据源。</p>
     */
    String[] value() default {};

    /**
     * 事务隔离级别（暂未实现，保留扩展）
     */
    int isolation() default -1;

    /**
     * 事务超时时间（秒，暂未实现，保留扩展）
     */
    int timeout() default -1;
}
