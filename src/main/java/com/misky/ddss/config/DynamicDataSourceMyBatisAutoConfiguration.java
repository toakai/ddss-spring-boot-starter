package com.misky.ddss.config;

import java.util.List;
import javax.sql.DataSource;

import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.misky.ddss.interceptor.DataSourceSqlLogInterceptor;
import com.misky.ddss.properties.DynamicDataSourceProperties;

/**
 * MyBatis 相关 Bean 的自动配置
 *
 * <p>独立配置类，仅当 MyBatis 在 classpath 上时才加载，
 * 避免项目未引入 MyBatis 时 JVM 无法加载该类。</p>
 *
 * <p>依赖 {@link DynamicDataSourceAutoConfiguration} 先完成，
 * 因此使用 {@code @AutoConfigureAfter} 保证加载顺序。</p>
 *
 * <h3>自动注册的 Bean</h3>
 * <ul>
 *   <li>{@code sqlSessionFactory} — MyBatis SqlSessionFactory</li>
 *   <li>{@code sqlSessionTemplate} — MyBatis SqlSessionTemplate</li>
 *   <li>{@code dataSourceSqlLogInterceptor} — SQL 执行日志拦截器（可选）</li>
 * </ul>
 *
 * @see DynamicDataSourceAutoConfiguration
 */
@org.springframework.context.annotation.Configuration
@ConditionalOnClass(SqlSessionFactoryBean.class)
@AutoConfigureAfter(DynamicDataSourceAutoConfiguration.class)
public class DynamicDataSourceMyBatisAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(
            DynamicDataSourceMyBatisAutoConfiguration.class);

    private final DynamicDataSourceProperties properties;

    public DynamicDataSourceMyBatisAutoConfiguration(DynamicDataSourceProperties properties) {
        this.properties = properties;
    }

    // ======================== MyBatis 核心 ========================

    /**
     * SqlSessionFactory — 仅当 MyBatis 在 classpath 上时才创建
     */
    @Bean(name = "sqlSessionFactory")
    @ConditionalOnProperty(prefix = "dp.datasource", name = "primary")
    public SqlSessionFactory sqlSessionFactory(
            @Qualifier("dynamicDataSource") DataSource dynamicDataSource,
            ObjectProvider<org.apache.ibatis.session.Configuration> configurationProvider,
            ObjectProvider<Interceptor> sqlLogInterceptorProvider) throws Exception {

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dynamicDataSource);

        // 加载 Mapper XML（如果配置了路径）
        String[] mapperLocations = properties.getMapperLocations();
        if (mapperLocations != null && mapperLocations.length > 0) {
            List<Resource> resources = new java.util.ArrayList<>();
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
        applyMyBatisConfig(factoryBean, configurationProvider);

        // 注入 SQL 日志拦截器（如果启用）
        Interceptor sqlLogInterceptor = sqlLogInterceptorProvider.getIfAvailable();
        if (sqlLogInterceptor != null) {
            factoryBean.setPlugins(new Interceptor[]{sqlLogInterceptor});
            log.info("已注入 MyBatis 插件：DataSourceSqlLogInterceptor");
        }

        return factoryBean.getObject();
    }

    @Bean
    @ConditionalOnClass(SqlSessionTemplate.class)
    public SqlSessionTemplate sqlSessionTemplate(
            @Qualifier("sqlSessionFactory") SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    // ======================== SQL 日志拦截器 ========================

    /**
     * SQL 执行日志拦截器（可选）
     * <p>通过 {@code dp.datasource.sql-log-enabled=true} 启用。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "dp.datasource", name = "sql-log-enabled", havingValue = "true")
    public Interceptor dataSourceSqlLogInterceptor() {
        log.info("已启用 SQL 执行日志拦截器");
        return new DataSourceSqlLogInterceptor();
    }

    // ======================== 辅助方法 ========================

    /**
     * 应用 MyBatis Configuration 选项（驼峰转换等）
     * <p>提取到独立方法以隔离 FQN，提升可读性。</p>
     */
    private void applyMyBatisConfig(
            SqlSessionFactoryBean factoryBean,
            ObjectProvider<org.apache.ibatis.session.Configuration> configurationProvider) {

        org.apache.ibatis.session.Configuration config =
                configurationProvider.getIfAvailable(
                        org.apache.ibatis.session.Configuration::new);
        if (properties.isMapUnderscoreToCamelCase()) {
            config.setMapUnderscoreToCamelCase(true);
        }
        factoryBean.setConfiguration(config);
    }
}
