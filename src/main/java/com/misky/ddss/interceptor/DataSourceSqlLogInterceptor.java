package com.misky.ddss.interceptor;

import java.util.Properties;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.misky.ddss.core.DynamicDataSource;

/**
 * MyBatis SQL 执行日志拦截器
 *
 * <p>在 DEBUG 日志中输出当前线程使用的数据源 key，方便排查数据源切换问题。</p>
 *
 * <p>通过配置 {@code dp.datasource.sql-log-enabled=true} 启用，
 * 默认不启用，避免生产环境日志量过大。</p>
 *
 * <p>日志示例：
 * <pre>[DEBUG] [DataSourceSqlLog] 数据源=cpdb-data-source, SQL={...}</pre>
 * </p>
 *
 * @see com.misky.ddss.properties.DynamicDataSourceProperties#isSqlLogEnabled()
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class,
                        org.apache.ibatis.session.ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class,
                        org.apache.ibatis.session.ResultHandler.class,
                        org.apache.ibatis.cache.CacheKey.class, org.apache.ibatis.mapping.BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class DataSourceSqlLogInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(DataSourceSqlLogInterceptor.class);

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String dataSourceKey = DynamicDataSource.getDataSource();
        Object[] args = invocation.getArgs();
        MappedStatement ms = (MappedStatement) args[0];

        if (log.isDebugEnabled()) {
            log.debug("[DataSourceSqlLog] 当前数据源：{}，Mapper 方法：{}.{}",
                    dataSourceKey, ms.getId());
        }

        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        return Interceptor.super.plugin(target);
    }

    @Override
    public void setProperties(Properties properties) {
        // 无需外部属性配置
    }
}
