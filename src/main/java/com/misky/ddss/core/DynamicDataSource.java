package com.misky.ddss.core;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

/**
 * 动态数据源
 *
 * <p>基于 ThreadLocal 保存当前线程的数据源标识，Spring 每次获取连接时
 * 调用 {@link #determineCurrentLookupKey()} 决定使用哪个数据源。</p>
 *
 * <p>数据源切换由 {@code DataSourceAspect} 在方法级别自动控制，
 * 也可手动调用 {@link #setDataSource(String)} 进行编程式切换。</p>
 */
public class DynamicDataSource extends AbstractRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSource.class);

    /** 当前线程绑定的数据源标识 */
    private static final ThreadLocal<String> CONTEXT_HOLDER = new ThreadLocal<>();

    /** 主库（默认）数据源标识 */
    private static String primaryDataSourceKey;

    public DynamicDataSource(DataSource defaultDataSource, String primaryKey,
                             Map<String, DataSource> targetDataSources) {
        super.setDefaultTargetDataSource(defaultDataSource);
        super.setTargetDataSources(new HashMap<>(targetDataSources));
        super.afterPropertiesSet();
        DynamicDataSource.primaryDataSourceKey = primaryKey;
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return getDataSource();
    }

    // ======================== 编程式 API ========================

    /**
     * 设置当前线程的数据源
     * @param dataSource 数据源标识（YAML 中的 key）
     */
    public static void setDataSource(String dataSource) {
        CONTEXT_HOLDER.set(dataSource);
        log.debug("切换数据源：{}", dataSource);
    }

    /**
     * 获取当前线程的数据源标识，未设置时回退到主库
     */
    public static String getDataSource() {
        String dataSource = CONTEXT_HOLDER.get();
        if (dataSource == null) {
            setDataSource(primaryDataSourceKey);
        }
        return CONTEXT_HOLDER.get();
    }

    /**
     * 清除当前线程的数据源标识（防止 ThreadLocal 泄漏）
     */
    public static void clearDataSource() {
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
     */
    public Map<Object, DataSource> getResolvedDataSources() {
        return super.getResolvedDataSources();
    }
}
