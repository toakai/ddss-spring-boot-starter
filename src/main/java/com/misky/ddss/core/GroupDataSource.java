package com.misky.ddss.core;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据源分组（读写分离 &amp; 负载均衡）
 *
 * <p>将多个同质数据源（如只读从库）聚合成一个逻辑数据源，
 * 每次获取连接时根据策略选择一个成员。</p>
 *
 * <h3>支持的策略</h3>
 * <ul>
 *   <li>{@link LoadBalanceStrategy#ROUND_ROBIN ROUND_ROBIN} — 轮询（默认）</li>
 *   <li>{@link LoadBalanceStrategy#RANDOM RANDOM} — 随机</li>
 * </ul>
 *
 * <h3>配置示例</h3>
 * <pre>
 * dp.datasource.groups.slaves:
 *   datasources: [slave1, slave2]
 *   strategy: ROUND_ROBIN
 * </pre>
 *
 * @see LoadBalanceStrategy
 * @see com.misky.ddss.config.DynamicDataSourceAutoConfiguration
 */
public class GroupDataSource implements DataSource {

    private static final Logger log = LoggerFactory.getLogger(GroupDataSource.class);

    private final String groupName;
    private final List<DataSource> members;
    private final LoadBalanceStrategy strategy;
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);

    public GroupDataSource(String groupName, List<DataSource> members,
                           LoadBalanceStrategy strategy) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException(
                    "数据源组 [" + groupName + "] 至少需要一个成员");
        }
        this.groupName = groupName;
        this.members = Collections.unmodifiableList(members);
        this.strategy = strategy != null ? strategy : LoadBalanceStrategy.ROUND_ROBIN;
    }

    /**
     * 获取组成员（只读视图）
     */
    public List<DataSource> getMembers() {
        return members;
    }

    /**
     * 当前使用的负载策略
     */
    public LoadBalanceStrategy getStrategy() {
        return strategy;
    }

    // ======================== DataSource 代理（含重试/fallback） ========================

    @Override
    public Connection getConnection() throws SQLException {
        return selectWithRetry(ds -> ds.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return selectWithRetry(ds -> ds.getConnection(username, password));
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return select().getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        // 设置到所有成员
        for (DataSource ds : members) {
            ds.setLogWriter(out);
        }
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return select().getLoginTimeout();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        for (DataSource ds : members) {
            ds.setLoginTimeout(seconds);
        }
    }

    @Override
    public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return select().getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        return select().unwrap(iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return select().isWrapperFor(iface);
    }

    // ======================== 内部 ========================

    /**
     * 按策略选择一个成员并尝试获取连接，失败时自动重试其余成员（fallback）。
     * <p>只有全部成员都失败时才抛出异常。</p>
     */
    @FunctionalInterface
    private interface ConnectionSupplier {
        Connection get(DataSource ds) throws SQLException;
    }

    private Connection selectWithRetry(ConnectionSupplier supplier) throws SQLException {
        int startIdx = selectIndex();
        SQLException lastException = null;

        // 从选中的索引开始，依次尝试所有成员
        for (int i = 0; i < members.size(); i++) {
            int idx = (startIdx + i) % members.size();
            DataSource ds = members.get(idx);
            try {
                Connection conn = supplier.get(ds);
                if (i > 0) {
                    log.info("数据源组 [{}]：主选成员 {} 不可用，已 fallback 到成员 {}",
                            groupName, startIdx, idx);
                }
                return conn;
            } catch (SQLException e) {
                lastException = e;
                log.warn("数据源组 [{}] 成员 [{}] 连接失败：{}", groupName, idx, e.getMessage());
            }
        }
        throw new SQLException(
                "数据源组 [" + groupName + "] 所有成员（共 " + members.size() + " 个）均连接失败",
                lastException);
    }

    /**
     * 按策略返回所选成员的索引（不获取连接，仅做路由选择）
     */
    private int selectIndex() {
        switch (strategy) {
            case RANDOM:
                return ThreadLocalRandom.current().nextInt(members.size());
            case ROUND_ROBIN:
            default: {
                int idx = Math.floorMod(roundRobinIndex.getAndIncrement(), members.size());
                return idx;
            }
        }
    }

    /**
     * 按策略选择一个成员数据源（无重试，仅路由选择）。
     * <p>用于 {@code getLogWriter()} 等非关键代理方法，
     * 以及被 {@code selectWithRetry} 复用。</p>
     */
    private DataSource select() {
        return members.get(selectIndex());
    }
}
