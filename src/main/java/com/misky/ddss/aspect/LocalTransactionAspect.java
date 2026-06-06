package com.misky.ddss.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.util.StringUtils;

import com.misky.ddss.annotation.LocalTransactional;
import com.misky.ddss.core.DynamicDataSource;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地多数据源事务切面
 *
 * <p>拦截标注了 {@link LocalTransactionl} 的方法，
 * 在多个数据源上分别开启事务，方法正常结束则依次提交，
 * 抛异常则依次回滚（Best-Efforts 1PC 模式）。</p>
 *
 * <p><b>局限性：</b>一旦某个事务已提交，后续事务提交失败无法回滚前者。
 * 如需强一致性，请使用 Seata 分布式事务。</p>
 *
 * <p>优先级：比 {@link com.misky.ddss.aspect.DataSourceAspect} 更低（后执行），
 * 确保数据源切换在事务范围内生效。</p>
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class LocalTransactionAspect {

    private static final Logger log = LoggerFactory.getLogger(LocalTransactionAspect.class);

    @Autowired
    private ApplicationContext applicationContext;

    // ==================== 切点 ====================

    @Around("@annotation(localTx)")
    public Object doAround(ProceedingJoinPoint point, LocalTransactional localTx) throws Throwable {
        String[] specifiedKeys = localTx.value();

        // 1. 收集需要参与事务的数据源 key
        List<String> dsKeys = resolveDsKeys(specifiedKeys);

        if (dsKeys.isEmpty()) {
            log.warn("LocalTransactional: 未找到任何参与事务的数据源，跳过事务管理");
            return point.proceed();
        }

        // 2. 为每个数据源开启事务
        List<PlatformTransactionManager> managers = new ArrayList<>();
        List<TransactionStatus> statuses = new ArrayList<>();
        List<String> activeKeys = new ArrayList<>();

        try {
            beginTransactions(dsKeys, managers, statuses, activeKeys);

            // 3. 执行业务方法
            Object result = point.proceed();

            // 4. 提交所有事务（按开启顺序）
            commitTransactions(managers, statuses, activeKeys);

            return result;
        } catch (Throwable t) {
            // 5. 回滚所有事务
            rollbackTransactions(managers, statuses, activeKeys, t);
            throw t;
        }
    }

    // ==================== 核心逻辑 ====================

    /**
     * 解析需要参与事务的数据源 key 列表
     * <p>如 @LocalTransactionl 指定了 value，直接使用；
     * 如未指定，从 DynamicDataSource 获取所有已注册的数据源 key。</p>
     */
    private List<String> resolveDsKeys(String[] specifiedKeys) {
        List<String> result = new ArrayList<>();

        if (specifiedKeys != null && specifiedKeys.length > 0) {
            for (String key : specifiedKeys) {
                if (StringUtils.hasText(key)) {
                    result.add(key.trim());
                }
            }
            return result;
        }

        // 未指定：使用 DynamicDataSource 中所有已注册的数据源
        DynamicDataSource ds = applicationContext.getBean(DynamicDataSource.class);
        if (ds != null && ds.getResolvedDataSources() != null) {
            for (Object key : ds.getResolvedDataSources().keySet()) {
                String keyStr = key.toString();
                // 跳过 GroupDataSource（它们是虚拟的，不是真实连接池）
                if (ds.getResolvedDataSources().get(key) instanceof com.misky.ddss.core.GroupDataSource) {
                    continue;
                }
                result.add(keyStr);
            }
        }

        return result;
    }

    /**
     * 为每个数据源开启事务
     */
    private void beginTransactions(
            List<String> dsKeys,
            List<PlatformTransactionManager> managers,
            List<TransactionStatus> statuses,
            List<String> activeKeys) {

        DynamicDataSource dynamicDs = applicationContext.getBean(DynamicDataSource.class);
        if (dynamicDs == null) {
            throw new IllegalStateException("未找到 DynamicDataSource Bean，无法开启本地多数据源事务");
        }

        for (String key : dsKeys) {
            javax.sql.DataSource targetDs = dynamicDs.getResolvedDataSources().get(key);
            if (targetDs == null) {
                throw new IllegalStateException(
                        "数据源 key [" + key + "] 未在 DynamicDataSource 中注册，"
                        + "已注册的数据源：" + dynamicDs.getResolvedDataSources().keySet());
            }
            // 跳过 LazyDataSourceProxy（未初始化时跳过，避免意外初始化）
            if (targetDs instanceof com.misky.ddss.core.LazyDataSourceProxy) {
                com.misky.ddss.core.LazyDataSourceProxy proxy =
                        (com.misky.ddss.core.LazyDataSourceProxy) targetDs;
                if (!proxy.isInitialized()) {
                    log.info("本地事务：跳过未初始化的懒加载数据源 [{}]", key);
                    continue;
                }
                targetDs = proxy.getTargetDataSource();
            }

            PlatformTransactionManager txManager = new DataSourceTransactionManager(targetDs);
            TransactionStatus status = txManager.getTransaction(new DefaultTransactionDefinition());

            managers.add(txManager);
            statuses.add(status);
            activeKeys.add(key);

            log.debug("本地事务：已开启数据源 [{}] 的事务", key);
        }
    }

    /**
     * 按开启顺序依次提交所有事务
     *
     * <p><b>注意：</b>如果前一个事务已提交，后一个提交失败，
     * 已提交的事务无法回滚。这是 Best-Efforts 1PC 的固有局限。</p>
     */
    private void commitTransactions(
            List<PlatformTransactionManager> managers,
            List<TransactionStatus> statuses,
            List<String> activeKeys) {

        for (int i = 0; i < managers.size(); i++) {
            String key = activeKeys.get(i);
            try {
                managers.get(i).commit(statuses.get(i));
                log.debug("本地事务：已提交数据源 [{}] 的事务", key);
            } catch (Exception e) {
                log.error("本地事务：提交数据源 [{}] 的事务失败，已提交的数据源无法回滚！"
                        + " 强烈建议引入 Seata 等分布式事务框架。错误：{}",
                        key, e.getMessage(), e);
                // 继续提交其余事务，尽量保证一致性
            }
        }
    }

    /**
     * 依次回滚所有已开启的事务
     * <p>已提交的事务（理论上不应出现，但防御性处理）无法回滚。</p>
     */
    private void rollbackTransactions(
            List<PlatformTransactionManager> managers,
            List<TransactionStatus> statuses,
            List<String> activeKeys,
            Throwable originalError) {

        for (int i = 0; i < managers.size(); i++) {
            String key = activeKeys.get(i);
            try {
                PlatformTransactionManager mgr = managers.get(i);
                TransactionStatus status = statuses.get(i);
                // 只有活跃的事务才能回滚
                if (!status.isCompleted()) {
                    mgr.rollback(status);
                    log.debug("本地事务：已回滚数据源 [{}] 的事务", key);
                }
            } catch (Exception e) {
                log.warn("本地事务：回滚数据源 [{}] 的事务失败：{}", key, e.getMessage(), e);
            }
        }
    }
}
