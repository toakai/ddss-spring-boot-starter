package com.misky.ddss.config;

import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.misky.ddss.core.DynamicDataSource;
import com.misky.ddss.health.DynamicDataSourceHealthIndicator;

/**
 * Actuator 健康检查自动配置
 *
 * <p>仅当 Spring Boot Actuator 在 classpath 上时才加载。</p>
 * <p>加载后自动向 {@code /actuator/health} 端点注册每个数据源的健康状态。</p>
 *
 * <p>和 {@link DynamicDataSourceAutoConfiguration} 分离的目的是：
 * Actuator 不在 classpath 时，主配置类依然能正常加载。</p>
 */
@Configuration
@ConditionalOnClass(HealthIndicator.class)
@AutoConfigureAfter(DynamicDataSourceAutoConfiguration.class)
public class DynamicDataSourceActuatorAutoConfiguration {

    @Bean
    public DynamicDataSourceHealthIndicator dynamicDataSourceHealthIndicator(
            DynamicDataSource dynamicDataSource) {
        DynamicDataSourceHealthIndicator indicator = new DynamicDataSourceHealthIndicator();
        indicator.setDynamicDataSource(dynamicDataSource);
        return indicator;
    }
}
