package com.misky.ddss.core;

import java.io.Closeable;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * 动态数据源
 *
 * <p>基于 ThreadLocal 栈保存当前线程的数据源标识，Spring 每次获取连接时
 * 调用 {@link #determineCurrentLookupKey()} 决定使用哪个数据源。</p>
 *
 * <p>数据源切换由 {@code DataSourceAspect} 在方法级别自动控制，
 * 也可手动调用 {@link #setDataSource(String)} 进行编程式切换。</p>
 *
 * <p>使用栈式 ThreadLocal 支持嵌套数据源切换：
 * 内层方法结束时自动恢复外层方法的数据源绑定。</p>
 *
 * <p>实现 {@link DisposableBean}，应用关闭时自动关闭所有数据源连接池。</p>
 */
public class DynamicDataSource extends AbstractRoutingDataSource implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSource.class);

    /** 当前线程绑定的数据源标识栈（支持嵌套切换） */
    private static final ThreadLocal<Deque<String>> CONTEXT_HOLDER =
            ThreadLocal.withInitial(ArrayDeque::new);

    /** 主库（默认）数据源标识 */
    private static String primaryDataSourceKey;

    /** 所有目标数据源，用于优雅关闭 */
    private Map<String, DataSource> targetDataSources;

    public DynamicDataSource(DataSource defaultDataSource, String primaryKey,
                             Map<String, DataSource> targetDataSources) {
        super.setDefaultTargetDataSource(defaultDataSource);
        super.setTargetDataSources(new HashMap<>(targetDataSources));
        super.afterPropertiesSet();
        DynamicDataSource.primaryDataSourceKey = primaryKey;
        this.targetDataSources = new HashMap<>(targetDataSources);
    }

    /**
     * 决定当前线程使用的数据源 key
     *
     * <p>委托给 {@link #getDataSource()}，保持逻辑单一来源。</p>
     */
    @Override
    protected Object determineCurrentLookupKey() {
        return getDataSource();
    }

    // ======================== 编程式 API ========================

    /**
     * 设置当前线程的数据源（入栈）
     * <p>支持嵌套切换：内层方法结束时自动恢复外层数据源</p>
     * @param dataSource 数据源标识（YAML 中的 key）
     */
    public static void setDataSource(String dataSource) {
        CONTEXT_HOLDER.get().push(dataSource);
        log.debug("切换数据源：{}", dataSource);
    }

    /**
     * 获取当前线程绑定的数据源标识（栈顶）
     *
     * <p>如果栈为空（未绑定），返回 {@link #getPrimaryDataSourceKey()} 主库标识。</p>
     */
    public static String getDataSource() {
        Deque<String> stack = CONTEXT_HOLDER.get();
        return stack.isEmpty() ? primaryDataSourceKey : stack.peek();
    }

    /**
     * 清除当前线程的数据源标识（出栈）
     * <p>弹出栈顶数据源，恢复为上一层（或主库）</p>
     */
    public static void clearDataSource() {
        Deque<String> stack = CONTEXT_HOLDER.get();
        if (!stack.isEmpty()) {
            String popped = stack.pop();
            log.debug("恢复数据源：{} → {}", popped,
                    stack.isEmpty() ? primaryDataSourceKey : stack.peek());
        }
    }

    /**
     * 完全清除当前线程的数据源绑定栈（用于测试和异常恢复）
     */
    public static void clearAll() {
        CONTEXT_HOLDER.remove();
    }

    /**
     * 获取主库数据源标识
     */
    public static String getPrimaryDataSourceKey() {
        return primaryDataSourceKey;
    }

    /**
     * 获取所有已解析的数据源（供测试和运维使用）
     *
     * @return 不可修改的视图，防止外部误修改内部状态
     */
    public Map<Object, DataSource> getResolvedDataSources() {
        return Collections.unmodifiableMap(super.getResolvedDataSources());
    }

    /**
     * 优雅关闭：应用关闭时自动关闭所有数据源连接池
     *
     * <p>实现 {@link DisposableBean} 接口，
     * Spring 容器销毁时自动调用此方法。</p>
     */
    @Override
    public void destroy() throws Exception {
        log.info("开始关闭所有数据源，共 {} 个", targetDataSources.size());
        for (Map.Entry<String, DataSource> entry : targetDataSources.entrySet()) {
            String key = entry.getKey();
            DataSource ds = entry.getValue();
            try {
                if (ds instanceof Closeable) {
                    ((Closeable) ds).close();
                    log.info("数据源 [{}] 已关闭", key);
                }
            } catch (Exception e) {
                log.warn("数据源 [{}] 关闭失败：{}", key, e.getMessage());
            }
        }
        targetDataSources.clear();
        log.info("所有数据源关闭完成");
    }
}
