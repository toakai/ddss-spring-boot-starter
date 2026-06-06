package com.misky.ddss.config;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.StringUtils;

import com.alibaba.druid.pool.DruidDataSource;

import com.misky.ddss.aspect.DataSourceAspect;
import com.misky.ddss.core.DynamicDataSource;
import com.misky.ddss.properties.DynamicDataSourceProperties;

/**
 * 多数据源自动配置
 *
 * <p>启用条件：配置了 {@code dp.datasource.datasources} 且至少有一个数据源。</p>
 *
 * <h3>自动注册的 Bean</h3>
 * <ul>
 *   <li>{@code dynamicDataSource} — 动态数据源（始终注册）</li>
 *   <li>{@code dataSourceAspect} — AOP 切面（始终注册）</li>
 *   <li>{@code platformTransactionManager} — 事务管理器（始终注册）</li>
 *   <li>{@code sqlSessionFactory} — MyBatis SqlSessionFactory（仅 MyBatis 可用时）</li>
 *   <li>{@code sqlSessionTemplate} — MyBatis SqlSessionTemplate（仅 MyBatis 可用时）</li>
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
@Import(DataSourceAspect.class)
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

        for (Map.Entry<String, Map<String, Object>> entry : dsConfigs.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> config = entry.getValue();
            DataSource ds = createDataSource(key, config);
            targetDataSources.put(key, ds);
            log.info("已注册数据源：{}", key);

            if (key.equals(primaryKey)) {
                primaryDataSource = ds;
            }
        }

        if (primaryDataSource == null) {
            throw new IllegalStateException(
                    "主库 key [" + primaryKey + "] 未在 dp.datasource.datasources 中找到，" +
                    "已注册的数据源：" + targetDataSources.keySet());
        }

        log.info("动态数据源初始化完成，共 {} 个数据源，主库：{}", targetDataSources.size(), primaryKey);
        return new DynamicDataSource(primaryDataSource, primaryKey, targetDataSources);
    }

    /**
     * SQL 执行日志拦截器（可选）
     * <p>通过 {@code dp.datasource.sql-log-enabled=true} 启用。</p>
     */
    @Bean
    @ConditionalOnClass(name = "org.apache.ibatis.plugin.Interceptor")
    @ConditionalOnMissingBean(name = "dataSourceSqlLogInterceptor")
    public org.apache.ibatis.plugin.Interceptor dataSourceSqlLogInterceptor() {
        if (properties.isSqlLogEnabled()) {
            log.info("已启用 SQL 执行日志拦截器");
            return new com.misky.ddss.interceptor.DataSourceSqlLogInterceptor();
        }
        return null;
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

            // 通过反射设置属性：去掉 type 后逐个 set
            for (Map.Entry<String, Object> prop : config.entrySet()) {
                if ("type".equals(prop.getKey())) {
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
            try (Connection ignored = dataSource.getConnection()) {
                log.info("数据源 [{}] 连接验证通过", key);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "数据源 [" + key + "] 连接验证失败，请检查 URL、用户名、密码", e);
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

    // ======================== MyBatis 集成（可选） ========================

    /**
     * SqlSessionFactory — 仅当 MyBatis 在 classpath 上时才创建
     */
    @Bean(name = "sqlSessionFactory")
    @ConditionalOnClass(SqlSessionFactoryBean.class)
    @ConditionalOnMissingBean(name = "sqlSessionFactory")
    public SqlSessionFactory sqlSessionFactory(
            @Qualifier("dynamicDataSource") DataSource dynamicDataSource,
            ObjectProvider<org.apache.ibatis.session.Configuration> configurationProvider,
            ObjectProvider<org.apache.ibatis.plugin.Interceptor> sqlLogInterceptorProvider) throws Exception {

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dynamicDataSource);

        // 加载 Mapper XML（如果配置了路径）
        String[] mapperLocations = properties.getMapperLocations();
        if (mapperLocations != null && mapperLocations.length > 0) {
            java.util.List<Resource> resources = new java.util.ArrayList<>();
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (String location : mapperLocations) {
                try {
                    Resource[] found = resolver.getResources(location);
                    java.util.Collections.addAll(resources, found);
                } catch (Exception e) {
                    log.warn("未找到 Mapper XML 文件 [{}]，跳过：{}", location, e.getMessage());
                }
            }
            if (!resources.isEmpty()) {
                factoryBean.setMapperLocations(resources.toArray(new Resource[0]));
                log.info("加载 Mapper XML 文件 {} 个", resources.size());
            }
        }

        // 驼峰转换
        org.apache.ibatis.session.Configuration configuration =
                configurationProvider.getIfAvailable(org.apache.ibatis.session.Configuration::new);
        if (properties.isMapUnderscoreToCamelCase()) {
            configuration.setMapUnderscoreToCamelCase(true);
        }
        factoryBean.setConfiguration(configuration);

        // 注入 SQL 日志拦截器（如果启用）
        org.apache.ibatis.plugin.Interceptor sqlLogInterceptor = sqlLogInterceptorProvider.getIfAvailable();
        if (sqlLogInterceptor != null) {
            factoryBean.setPlugins(new org.apache.ibatis.plugin.Interceptor[]{sqlLogInterceptor});
            log.info("已注入 MyBatis 插件：DataSourceSqlLogInterceptor");
        }

        return factoryBean.getObject();
    }

    @Bean
    @ConditionalOnClass(SqlSessionTemplate.class)
    @ConditionalOnMissingBean(SqlSessionTemplate.class)
    public SqlSessionTemplate sqlSessionTemplate(
            @Qualifier("sqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }
}
