package com.misky.ddss.config;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

import com.alibaba.druid.pool.DruidDataSource;

import com.misky.ddss.aspect.DataSourceAspect;
import com.misky.ddss.aspect.LocalTransactionAspect;
import com.misky.ddss.core.DynamicDataSource;
import com.misky.ddss.core.GroupDataSource;
import com.misky.ddss.core.LazyDataSourceProxy;
import com.misky.ddss.core.LoadBalanceStrategy;
import com.misky.ddss.properties.DynamicDataSourceProperties;

/**
 * 多数据源自动配置
 *
 * <p>启用条件：配置了 {@code dp.datasource.datasources} 且至少有一个数据源。</p>
 *
 * <h3>自动注册的 Bean（主配置类）</h3>
 * <ul>
 *   <li>{@code dynamicDataSource} — 动态数据源（始终注册）</li>
 *   <li>{@code dataSourceAspect} — AOP 切面（始终注册）</li>
 *   <li>{@code localTransactionAspect} — 本地多数据源事务切面（始终注册）</li>
 *   <li>{@code platformTransactionManager} — 事务管理器（始终注册）</li>
 * </ul>
 *
 * <h3>MyBatis 集成（可选，需 MyBatis 在 classpath 上）</h3>
 * <p>由 {@link DynamicDataSourceMyBatisAutoConfiguration} 负责注册：</p>
 * <ul>
 *   <li>{@code sqlSessionFactory} — MyBatis SqlSessionFactory</li>
 *   <li>{@code sqlSessionTemplate} — MyBatis SqlSessionTemplate</li>
 *   <li>{@code dataSourceSqlLogInterceptor} — SQL 执行日志拦截器（需显式启用）</li>
 * </ul>
 *
 * <h3>如何引入</h3>
 * <ol>
 *   <li>添加 Maven 依赖：
 *     <pre>{@code
 * <dependency>
 *     <groupId>com.misky</groupId>
 *     <artifactId>ddss-spring-boot-starter</artifactId>
 *     <version>1.0.0</version>
 * </dependency>
 *     }</pre>
 *   </li>
 *   <li>在 YAML 中配置数据源（见 {@link DynamicDataSourceProperties}）</li>
 *   <li>在启动类排除 Spring Boot 默认数据源自动配置：
 *     <pre>{@code
 * @SpringBootApplication(exclude = {
 *     DataSourceAutoConfiguration.class,
 *     DataSourceTransactionManagerAutoConfiguration.class,
 *     DruidDataSourceAutoConfigure.class
 * })
 *     }</pre>
 *   </li>
 *   <li>在 Mapper 或 Service 上使用 {@code @DataSource("your-key")} 切换数据源</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(DynamicDataSourceProperties.class)
@ConditionalOnProperty(prefix = "dp.datasource", name = "primary")
public class DynamicDataSourceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceAutoConfiguration.class);

    private final DynamicDataSourceProperties properties;

    public DynamicDataSourceAutoConfiguration(DynamicDataSourceProperties properties) {
        this.properties = properties;
    }

    // ======================== 核心数据源 ========================

    @Bean(name = "dynamicDataSource")
    @ConditionalOnMissingBean(name = "dynamicDataSource")
    public DynamicDataSource dynamicDataSource() {
        String primaryKey = properties.getPrimary();
        Map<String, Map<String, Object>> dsConfigs = properties.getDatasources();

        if (dsConfigs == null || dsConfigs.isEmpty()) {
            throw new IllegalStateException(
                    "未配置 dp.datasource.datasources，请至少配置一个数据源");
        }
        if (!StringUtils.hasText(primaryKey)) {
            throw new IllegalStateException(
                    "请配置 dp.datasource.primary 以指定主库数据源 key");
        }

        Map<String, DataSource> targetDataSources = new HashMap<>();
        DataSource primaryDataSource = null;

        // 1. 创建各数据源（支持懒加载）
        for (Map.Entry<String, Map<String, Object>> entry : dsConfigs.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> config = entry.getValue();
            boolean lazy = isLazy(config);

            DataSource ds;
            if (lazy) {
                // 懒加载：创建代理，首次访问时才真正创建连接池
                ds = new LazyDataSourceProxy(key, () -> createDataSource(key, config));
                log.info("已注册数据源 [{}]（懒加载）", key);
            } else {
                ds = createDataSource(key, config);
                log.info("已注册数据源：{}", key);
            }
            targetDataSources.put(key, ds);

            if (key.equals(primaryKey)) {
                primaryDataSource = ds;
            }
        }

        // 2. 处理数据源分组（读写分离 / 负载均衡）
        Map<String, DynamicDataSourceProperties.GroupConfig> groups = properties.getGroups();
        if (groups != null && !groups.isEmpty()) {
            for (Map.Entry<String, DynamicDataSourceProperties.GroupConfig> entry : groups.entrySet()) {
                String groupName = entry.getKey();
                DynamicDataSourceProperties.GroupConfig groupConfig = entry.getValue();
                List<String> groupMembers = groupConfig.getDatasources();
                LoadBalanceStrategy strategy = groupConfig.getStrategy();

                if (groupMembers == null || groupMembers.isEmpty()) {
                    log.warn("数据源组 [{}] 未配置成员，跳过", groupName);
                    continue;
                }

                List<DataSource> members = new ArrayList<>();
                for (String memberKey : groupMembers) {
                    DataSource memberDs = targetDataSources.get(memberKey);
                    if (memberDs == null) {
                        throw new IllegalStateException(
                                "数据源组 [" + groupName + "] 引用的成员 [" + memberKey +
                                "] 未在 dp.datasource.datasources 中定义。" +
                                "已定义的数据源：" + targetDataSources.keySet());
                    }
                    members.add(memberDs);
                }

                GroupDataSource groupDs = new GroupDataSource(groupName, members, strategy);
                targetDataSources.put(groupName, groupDs);
                log.info("已注册数据源组 [{}]：成员={}, 策略={}", groupName, groupMembers, strategy);
            }
        }

        if (primaryDataSource == null) {
            throw new IllegalStateException(
                    "主库 key [" + primaryKey + "] 未在 dp.datasource.datasources 中找到，" +
                    "已注册的数据源：" + targetDataSources.keySet());
        }

        log.info("动态数据源初始化完成，共 {} 个数据源（含分组），主库：{}",
                targetDataSources.size(), primaryKey);
        return new DynamicDataSource(primaryDataSource, primaryKey, targetDataSources);
    }

    /**
     * 判断数据源是否配置为懒加载
     */
    private static boolean isLazy(Map<String, Object> config) {
        Object lazy = config.get("lazy");
        if (lazy == null) {
            return false;
        }
        if (lazy instanceof Boolean) {
            return (Boolean) lazy;
        }
        return "true".equalsIgnoreCase(lazy.toString());
    }

    // ======================== AOP 切面 ========================

    @Bean
    public DataSourceAspect dataSourceAspect() {
        return new DataSourceAspect();
    }

    @Bean
    public LocalTransactionAspect localTransactionAspect() {
        return new LocalTransactionAspect();
    }

    /**
     * 根据配置创建 DataSource 实例
     * <p>支持 Druid（默认）和 HikariCP，也可通过 {@code type} 指定其他实现</p>
     */
    @SuppressWarnings("unchecked")
    private DataSource createDataSource(String key, Map<String, Object> config) {
        String typeStr = (String) config.getOrDefault("type",
                "com.alibaba.druid.pool.DruidDataSource");
        try {
            Class<? extends DataSource> type = (Class<? extends DataSource>) Class.forName(typeStr);
            DataSource dataSource = type.getDeclaredConstructor().newInstance();

            // 通过反射设置属性：去掉 type/lazy 等保留字后逐个 set
            for (Map.Entry<String, Object> prop : config.entrySet()) {
                if ("type".equals(prop.getKey()) || "lazy".equals(prop.getKey())) {
                    continue;
                }
                // 尝试设置属性（跳过不存在 setter 的属性，如 druid 子节点）
                try {
                    String setterName = "set" + Character.toUpperCase(prop.getKey().charAt(0))
                            + prop.getKey().substring(1);
                    java.beans.BeanInfo beanInfo = java.beans.Introspector.getBeanInfo(type);
                    for (java.beans.PropertyDescriptor pd : beanInfo.getPropertyDescriptors()) {
                        if (pd.getName().equals(prop.getKey()) && pd.getWriteMethod() != null) {
                            Object value = prop.getValue();
                            // 类型转换
                            Class<?> propType = pd.getPropertyType();
                            if (value != null && !propType.isInstance(value)) {
                                if (propType == int.class || propType == Integer.class) {
                                    value = Integer.valueOf(value.toString());
                                } else if (propType == long.class || propType == Long.class) {
                                    value = Long.valueOf(value.toString());
                                } else if (propType == boolean.class || propType == Boolean.class) {
                                    value = Boolean.valueOf(value.toString());
                                } else if (propType == String.class) {
                                    // YAML 中 password: 123 会被解析为整数，需转为字符串
                                    value = value.toString();
                                }
                            }
                            pd.getWriteMethod().invoke(dataSource, value);
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.debug("跳过属性 [{}]（无对应 setter 或类型不兼容）：{}", prop.getKey(), e.getMessage());
                }
            }
            // 连接验证（fail-fast：配置错误在启动时暴露）
            // 可通过 dp.datasource.connection-validation-enabled=false 关闭
            if (properties.isConnectionValidationEnabled()) {
                try (Connection ignored = dataSource.getConnection()) {
                    log.info("数据源 [{}] 连接验证通过", key);
                }
            } else {
                log.info("数据源 [{}] 已创建（跳过连接验证）", key);
            }

            return dataSource;
        } catch (Exception e) {
            throw new IllegalStateException("创建数据源 [" + key + "] 失败", e);
        }
    }

    // ======================== 事务管理器 ========================

    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    public PlatformTransactionManager platformTransactionManager(
            @Qualifier("dynamicDataSource") DataSource dynamicDataSource) {
        return new DataSourceTransactionManager(dynamicDataSource);
    }
}
