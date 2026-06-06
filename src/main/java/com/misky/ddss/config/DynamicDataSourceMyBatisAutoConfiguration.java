package com.misky.ddss.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;

import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
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
 * 使用 {@code @AutoConfigureAfter} 保证加载顺序。</p>
 *
 * <h3>拦截器注册策略</h3>
 * <p>采用 <b>后补模式</b>：创建 {@code SqlSessionFactory} 时不急于设置拦截器，
 * 通过实现 {@link SmartInitializingSingleton} 在所有 Bean 就绪后，
 * 从 Spring 容器中收集全部 {@link Interceptor} Bean，
 * 再通过 {@link org.apache.ibatis.session.Configuration#addInterceptor(Interceptor)} 追加。</p>
 *
 * <p>这解决了与 PageHelper 等带有 {@code @ConditionalOnBean(SqlSessionFactory.class)}
 * 的第三方拦截器的鸡-蛋时序问题。</p>
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
@AutoConfigureBefore(name = "com.github.pagehelper.autoconfigure.PageHelperAutoConfiguration")
public class DynamicDataSourceMyBatisAutoConfiguration implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(
            DynamicDataSourceMyBatisAutoConfiguration.class);

    private final DynamicDataSourceProperties properties;
    private SqlSessionFactory sqlSessionFactory;

    @Autowired
    private ApplicationContext applicationContext;

    public DynamicDataSourceMyBatisAutoConfiguration(DynamicDataSourceProperties properties) {
        this.properties = properties;
    }

    // ======================== MyBatis 核心 ========================

    /**
     * SqlSessionFactory — 仅当 MyBatis 在 classpath 上时才创建
     *
     * <p>创建时不急于注入拦截器（因为第三方拦截器如 PageHelper 的
     * {@code PageInterceptor} 可能尚未就绪），拦截器将在
     * {@link #afterSingletonsInstantiated()} 中统一后补。</p>
     */
    @Bean(name = "sqlSessionFactory")
    @ConditionalOnProperty(prefix = "dp.datasource", name = "primary")
    public SqlSessionFactory sqlSessionFactory(
            @Qualifier("dynamicDataSource") DataSource dynamicDataSource,
            ObjectProvider<org.apache.ibatis.session.Configuration> configurationProvider)
            throws Exception {

        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dynamicDataSource);

        // 加载 Mapper XML（如果配置了路径）
        String[] mapperLocations = properties.getMapperLocations();
        if (mapperLocations != null && mapperLocations.length > 0) {
            List<Resource> resources = new ArrayList<>();
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

        // 不在此时设置拦截器 — afterSingletonsInstantiated() 统一后补
        this.sqlSessionFactory = factoryBean.getObject();
        return this.sqlSessionFactory;
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

    // ======================== SmartInitializingSingleton ========================

    /**
     * 所有单例 Bean 初始化完毕后，从 Spring 容器收集全部 {@link Interceptor} Bean，
     * 统一追加到 MyBatis {@link Configuration}。
     *
     * <p>时机：晚于所有 Bean 的 {@code @PostConstruct} 和依赖注入，
     * 早于 {@code ContextRefreshedEvent} 和应用就绪。</p>
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (sqlSessionFactory == null) {
            log.debug("sqlSessionFactory 为 null，跳过后补拦截器");
            return;
        }

        Map<String, Interceptor> interceptorBeans =
                applicationContext.getBeansOfType(Interceptor.class);
        if (interceptorBeans.isEmpty()) {
            log.debug("未发现 MyBatis 拦截器 Bean，跳过后补注入");
            return;
        }

        Configuration config = sqlSessionFactory.getConfiguration();
        List<String> addedNames = new ArrayList<>();

        for (Interceptor interceptor : interceptorBeans.values()) {
            // SQL 日志拦截器仅在启用时才追加
            if (interceptor instanceof DataSourceSqlLogInterceptor
                    && !properties.isSqlLogEnabled()) {
                continue;
            }
            config.addInterceptor(interceptor);
            addedNames.add(interceptor.getClass().getSimpleName());
        }

        if (!addedNames.isEmpty()) {
            log.info("后补注入 MyBatis 插件 {} 个：{}",
                    addedNames.size(), String.join(", ", addedNames));
        }
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
