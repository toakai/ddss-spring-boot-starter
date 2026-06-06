package com.misky.ddss.health;

import java.sql.Connection;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;

import com.misky.ddss.core.DynamicDataSource;

/**
 * 多数据源健康检查
 *
 * <p>由 {@link com.misky.ddss.config.DynamicDataSourceActuatorAutoConfiguration} 注册为 Bean，
 * 仅当 Spring Boot Actuator 在 classpath 上时生效。</p>
 *
 * <p>注册后可通过 {@code /actuator/health} 端点查看每个数据源的状态。</p>
 *
 * <p>响应示例：
 * <pre>{@code
 * {
 *   "ddss": {
 *     "status": "UP",
 *     "details": {
 *       "master": "UP",
 *       "slave": "UP"
 *     }
 *   }
 * }
 * }</pre>
 * </p>
 */
public class DynamicDataSourceHealthIndicator extends AbstractHealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(DynamicDataSourceHealthIndicator.class);

    private DynamicDataSource dynamicDataSource;

    public void setDynamicDataSource(DynamicDataSource dynamicDataSource) {
        this.dynamicDataSource = dynamicDataSource;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        Map<Object, DataSource> dataSources = dynamicDataSource.getResolvedDataSources();
        builder.up();

        if (dataSources == null || dataSources.isEmpty()) {
            builder.withDetail("message", "没有已解析的数据源");
            return;
        }

        int upCount = 0;
        int downCount = 0;

        for (Map.Entry<Object, DataSource> entry : dataSources.entrySet()) {
            String key = String.valueOf(entry.getKey());
            DataSource ds = entry.getValue();
            try (Connection ignored = ds.getConnection()) {
                builder.withDetail(key, "UP");
                upCount++;
                log.debug("数据源 [{}] 健康检查：UP", key);
            } catch (Exception e) {
                builder.withDetail(key, "DOWN - " + e.getMessage());
                downCount++;
                log.warn("数据源 [{}] 健康检查：DOWN，原因：{}", key, e.getMessage());
            }
        }

        builder.withDetail("total", dataSources.size());
        builder.withDetail("up", upCount);
        builder.withDetail("down", downCount);

        if (downCount > 0) {
            builder.status("DEGRADED");
        }
    }
}
