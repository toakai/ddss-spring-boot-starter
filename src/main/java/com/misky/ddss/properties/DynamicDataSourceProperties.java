package com.misky.ddss.properties;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 多数据源配置属性
 *
 * <h3>YAML 示例</h3>
 * <pre>
 * dp:
 *   datasource:
 *     primary: master                       # 主库 key
 *     map-underscore-to-camel-case: true    # 驼峰转换（默认 true）
 *     mapper-locations:                     # 可选：Mapper XML 路径
 *       - classpath*:mapper/**&#47;*.xml
 *     datasources:
 *       master:
 *         type: com.alibaba.druid.pool.DruidDataSource
 *         driver-class-name: com.mysql.cj.jdbc.Driver
 *         url: jdbc:mysql://localhost:3306/mydb
 *         username: root
 *         password: 123456
 *         druid:
 *           initial-size: 5
 *           max-active: 20
 *       slave:
 *         driver-class-name: ...
 *         url: jdbc:mysql://localhost:3307/mydb
 *         username: root
 *         password: 123456
 * </pre>
 */
@ConfigurationProperties(prefix = "dp.datasource")
public class DynamicDataSourceProperties {

    /** 主库数据源 key（必填） */
    private String primary;

    /** Mapper XML 文件路径（可选，用于 MyBatis） */
    private String[] mapperLocations;

    /** MyBatis 是否开启驼峰命名转换，默认 true */
    private boolean mapUnderscoreToCamelCase = true;

    /**
     * 是否启用 SQL 执行日志（DEBUG 级别）
     * <p>开启后，每次 MyBatis 执行 SQL 时会输出当前使用的数据源 key，
     * 方便排查数据源切换问题。</p>
     * <p>默认 false，避免生产环境日志量过大。</p>
     */
    private boolean sqlLogEnabled = false;

    /**
     * 所有数据源配置，key 为数据源标识（如 master、slave），value 为连接池参数
     * <p>支持 Druid 和 HikariCP 两种连接池实现</p>
     */
    private Map<String, Map<String, Object>> datasources = new LinkedHashMap<>();

    // ==================== getters & setters ====================

    public String getPrimary() {
        return primary;
    }

    public void setPrimary(String primary) {
        this.primary = primary;
    }

    public String[] getMapperLocations() {
        return mapperLocations;
    }

    public void setMapperLocations(String[] mapperLocations) {
        this.mapperLocations = mapperLocations;
    }

    public boolean isMapUnderscoreToCamelCase() {
        return mapUnderscoreToCamelCase;
    }

    public void setMapUnderscoreToCamelCase(boolean mapUnderscoreToCamelCase) {
        this.mapUnderscoreToCamelCase = mapUnderscoreToCamelCase;
    }

    public boolean isSqlLogEnabled() {
        return sqlLogEnabled;
    }

    public void setSqlLogEnabled(boolean sqlLogEnabled) {
        this.sqlLogEnabled = sqlLogEnabled;
    }

    public Map<String, Map<String, Object>> getDatasources() {
        return datasources;
    }

    public void setDatasources(Map<String, Map<String, Object>> datasources) {
        this.datasources = datasources;
    }
}
