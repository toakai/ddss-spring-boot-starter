package com.misky.ddss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.zaxxer.hikari.HikariDataSource;

import com.misky.ddss.core.DynamicDataSource;

/**
 * DynamicDataSource 单元测试（不依赖 Spring 上下文）
 */
class DynamicDataSourceTest {

    private HikariDataSource masterDs;
    private HikariDataSource slaveDs;
    private DynamicDataSource dynamicDataSource;

    @BeforeEach
    void setUp() {
        masterDs = new HikariDataSource();
        masterDs.setJdbcUrl("jdbc:h2:mem:master;DB_CLOSE_DELAY=-1");
        masterDs.setUsername("sa");
        masterDs.setPassword("");
        masterDs.setDriverClassName("org.h2.Driver");

        slaveDs = new HikariDataSource();
        slaveDs.setJdbcUrl("jdbc:h2:mem:slave;DB_CLOSE_DELAY=-1");
        slaveDs.setUsername("sa");
        slaveDs.setPassword("");
        slaveDs.setDriverClassName("org.h2.Driver");

        Map<String, DataSource> targetDataSources = new HashMap<>();
        targetDataSources.put("master", masterDs);
        targetDataSources.put("slave", slaveDs);

        dynamicDataSource = new DynamicDataSource(masterDs, "master", targetDataSources);
    }

    @Test
    void testPrimaryDataSource() {
        assertEquals("master", DynamicDataSource.getPrimaryDataSourceKey());
    }

    @Test
    void testSwitchDataSource() {
        DynamicDataSource.setDataSource("slave");
        assertEquals("slave", DynamicDataSource.getDataSource());
    }

    @Test
    void testDefaultToPrimary() {
        DynamicDataSource.clearDataSource();
        assertEquals("master", DynamicDataSource.getDataSource());
    }

    @Test
    void testClearDataSource() {
        DynamicDataSource.setDataSource("slave");
        DynamicDataSource.clearDataSource();
        // 清除后再次获取应该回到主库
        assertEquals("master", DynamicDataSource.getDataSource());
    }

    @Test
    void testResolvedDataSources() {
        Map<Object, DataSource> resolved = dynamicDataSource.getResolvedDataSources();
        assertNotNull(resolved);
        assertEquals(2, resolved.size());
        assertTrue(resolved.containsKey("master"));
        assertTrue(resolved.containsKey("slave"));
    }

    @Test
    void testThreadLocalIsolation() throws InterruptedException {
        DynamicDataSource.setDataSource("slave");
        assertEquals("slave", DynamicDataSource.getDataSource());

        // 新线程应该不受影响
        Thread thread = new Thread(() -> {
            assertEquals("master", DynamicDataSource.getDataSource());
        });
        thread.start();
        thread.join();
    }
}
