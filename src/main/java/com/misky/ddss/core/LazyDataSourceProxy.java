package com.misky.ddss.core;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.slf4j.LoggerFactory;

/**
 * 数据源懒加载代理
 *
 * <p>包装一个 DataSource 工厂，在第一次调用 {@link #getConnection()} 时
 * 才真正创建底层数据源和连接池，避免启动时创建不常用的数据源。</p>
 *
 * <p>线程安全：使用 DCL（Double-Checked Locking）+ volatile 保证。</p>
 *
 * <h3>使用方式</h3>
 * 在 YAML 中配置 {@code lazy: true}：
 * <pre>
 * dp.datasource.datasources.report:
 *   lazy: true
 *   type: com.alibaba.druid.pool.DruidDataSource
 *   url: jdbc:mysql://...
 * </pre>
 *
 * @see com.misky.ddss.config.DynamicDataSourceAutoConfiguration
 */
public class LazyDataSourceProxy implements DataSource, Closeable {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(LazyDataSourceProxy.class);

    private final String key;
    private final Supplier<DataSource> factory;
    private final Object lock = new Object();

    /** volatile 保证 DCL 可见性 */
    private volatile DataSource delegate;

    public LazyDataSourceProxy(String key, Supplier<DataSource> factory) {
        this.key = key;
        this.factory = factory;
    }

    /**
     * 是否已完成初始化（底层真实数据源已创建）
     */
    public boolean isInitialized() {
        return delegate != null;
    }

    /**
     * 获取底层真实数据源（仅初始化后可用）
     * <p>用于需要访问真实连接池配置的场景（如本地事务管理）。</p>
     */
    public DataSource getTargetDataSource() {
        return delegate;
    }

    // ======================== DataSource 代理 ========================

    @Override
    public Connection getConnection() throws SQLException {
        return getDelegate().getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getDelegate().getConnection(username, password);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return getDelegate().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        getDelegate().setLogWriter(out);
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return getDelegate().getLoginTimeout();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        getDelegate().setLoginTimeout(seconds);
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return getDelegate().getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return getDelegate().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return getDelegate().isWrapperFor(iface);
    }

    // ======================== Closeable ========================

    /**
     * 关闭底层数据源（如果已初始化）
     * <p>未初始化的懒加载数据源无需关闭。</p>
     */
    @Override
    public void close() throws IOException {
        if (delegate instanceof Closeable) {
            ((Closeable) delegate).close();
            log.info("懒加载数据源 [{}] 已关闭", key);
        }
    }

    // ======================== 内部 ========================

    /**
     * DCL 获取底层真实数据源，首次调用时触发初始化
     */
    private DataSource getDelegate() {
        if (delegate == null) {
            synchronized (lock) {
                if (delegate == null) {
                    delegate = factory.get();
                    log.info("懒加载数据源 [{}] 首次连接，已创建连接池", key);
                }
            }
        }
        return delegate;
    }
}
