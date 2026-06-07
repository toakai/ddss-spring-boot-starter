# ddss-spring-boot-starter

**D**ynamic **D**ata**S**ource **S**pring Boot Starter — 基于 Spring Boot 的轻量级多数据源动态切换组件。

## 目录

- [1. 快速开始](#1-快速开始)
- [2. 配置说明](#2-配置说明)
- [3. @DataSource 注解](#3-datasource-注解)
- [4. SpEL 动态数据源](#4-spel-动态数据源)
- [5. 懒加载数据源](#5-懒加载数据源)
- [6. 读写分离 & 负载均衡](#6-读写分离--负载均衡)
- [7. 本地多数据源事务](#7-本地多数据源事务)
- [8. 健康检查](#8-健康检查)
- [9. SQL 日志拦截器](#9-sql-日志拦截器)
- [10. 完整实战示例](#10-完整实战示例)
- [11. 配置参考](#11-配置参考)
- [12. 架构说明](#12-架构说明)
- [13. 常见问题](#13-常见问题)

---

## 1. 快速开始

### 1.1 添加依赖

```xml
<dependency>
    <groupId>com.misky</groupId>
    <artifactId>ddss-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

> **依赖说明**：MyBatis 和 Spring Actuator 为可选依赖，未引入时不加载对应功能。

### 1.2 最小配置

```yaml
dp:
  datasource:
    primary: master
    datasources:
      master:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://localhost:3306/mydb
        username: root
        password: 123456
        druid:
          initial-size: 5
          max-active: 20
      slave:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://localhost:3307/mydb
        username: root
        password: 123456
        druid:
          initial-size: 2
          max-active: 10
```

只需以上配置，即可在代码中使用 `@DataSource` 切换数据源。

---

## 2. 配置说明

### 2.1 基础属性

| 属性 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `dp.datasource.primary` | String | — | **必填**，主库数据源 key |
| `dp.datasource.datasources` | Map | — | **必填**，所有数据源配置 |
| `dp.datasource.map-underscore-to-camel-case` | boolean | `true` | MyBatis 驼峰转换（需 MyBatis） |
| `dp.datasource.mapper-locations` | String[] | — | Mapper XML 路径（需 MyBatis） |
| `dp.datasource.connection-validation-enabled` | boolean | `true` | 启动时是否验证连接（fail-fast） |
| `dp.datasource.sql-log-enabled` | boolean | `false` | 是否启用 SQL 日志拦截器 |

### 2.2 数据源类型

支持 **Druid** 和 **HikariCP** 两种连接池：

```yaml
# Druid 示例
dp.datasource.datasources.master:
  type: com.alibaba.druid.pool.DruidDataSource
  driver-class-name: com.mysql.cj.jdbc.Driver
  url: jdbc:mysql://localhost:3306/mydb
  username: root
  password: 123456
  druid:
    initial-size: 5
    max-active: 20

# HikariCP 示例
dp.datasource.datasources.report:
  type: com.zaxxer.hikari.HikariDataSource
  driver-class-name: com.mysql.cj.jdbc.Driver
  jdbc-url: jdbc:mysql://localhost:3306/report_db
  username: root
  password: 123456
  maximum-pool-size: 10
```

> **注意**：Druid 的连接 URL 属性名为 `url`，HikariCP 为 `jdbc-url`。

### 2.3 连接验证开关

默认启动时会立即验证每个数据源连接（fail-fast）。若数据库启动顺序不可控，可关闭：

```yaml
dp:
  datasource:
    connection-validation-enabled: false  # 延迟到首次业务请求时验证
```

---

## 3. @DataSource 注解

### 3.1 基础用法

在 Service 方法上标注 `@DataSource("key")` 即可切换数据源：

```java
@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;

    // 使用主库
    public Order findById(Long id) {
        return orderMapper.selectById(id);
    }

    // 切换到从库
    @DataSource("slave")
    public List<Order> findRecentOrders() {
        return orderMapper.selectRecent();
    }
}
```

### 3.2 嵌套切换（栈式）

AOP 切面使用 **栈（Deque）** 管理数据源 key，天然支持 AOP 嵌套调用：

```java
@DataSource("slave")    // 外层 → 栈顶: slave
public List<Order> getOrders() {
    // 当前数据源: slave
    return orderMapper.selectAll();
}

// 同一个 Service 内，内层方法用不同数据源
@DataSource("report")   // 内层 → 栈顶: report (下层仍是 slave)
public void exportReport() {
    // 当前数据源: report
    // 方法结束后，自动恢复为 slave
}
```

**栈式切换规则：**
- `@DataSource("xxx")` 方法进入 → push → 当前数据源 = 栈顶
- 方法退出 → pop → 当前数据源 = 新栈顶（或主库）
- 线程隔离：每个线程有独立的栈

---

## 4. SpEL 动态数据源

当 `@DataSource` 的值以 `#` 开头时，自动解析为 SpEL 表达式，支持运行时动态决定数据源。

### 4.1 方法参数

```java
// 根据方法参数动态选择数据源
@DataSource("#tenantId")
public List<User> getUsersByTenant(String tenantId) {
    return userMapper.selectByTenant(tenantId);
}

// 调用 getUsersByTenant("tenant_a") → 数据源切换到 "tenant_a"
// 调用 getUsersByTenant("tenant_b") → 数据源切换到 "tenant_b"
```

### 4.2 实体属性

```java
@DataSource("#user.tenantId")
public void saveUser(User user) {
    userMapper.insert(user);
}
```

> **当前支持**：方法参数作为 SpEL 变量（`#paramName`）。对象属性（`#obj.field`）支持取决于 Spring Expression 的 property accessor，一般可用。

---

## 5. 懒加载数据源

对于不常用的数据源（如报表库、归档库），可启用懒加载，启动时不创建连接池，首次使用时才初始化。

```yaml
dp:
  datasource:
    primary: master
    datasources:
      master:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://localhost:3306/mydb
        username: root
        password: 123456
        druid:
          initial-size: 5
          max-active: 20
      archive:
        lazy: true          # ← 启用懒加载
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://archived-db:3306/archive
        username: root
        password: 123456
```

**特性：**
- `archive` 数据源在启动时不创建连接池
- 首次被 `@DataSource("archive")` 访问时才初始化
- 健康检查显示 `LAZY (not yet initialized)` 且不触发初始化
- 使用 DCL（Double-Checked Locking）+ `volatile` 保证线程安全

---

## 6. 读写分离 & 负载均衡

### 6.1 分组配置

将多个数据源定义为一组，通过 `@DataSource("组名")` 按负载均衡策略选择：

```yaml
dp:
  datasource:
    primary: master
    datasources:
      master:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://localhost:3306/mydb
        username: root
        password: 123456
      slave1:
        ...
        url: jdbc:mysql://192.168.1.11:3306/mydb
      slave2:
        ...
        url: jdbc:mysql://192.168.1.12:3306/mydb
    groups:
      slaves:                          # 分组名
        datasources: [slave1, slave2]  # 成员列表
        strategy: ROUND_ROBIN          # 负载策略（可选）
```

### 6.2 负载策略

| 策略 | 枚举值 | 说明 |
|------|--------|------|
| 轮询 | `ROUND_ROBIN`（默认） | 依次轮流选择 |
| 随机 | `RANDOM` | 随机选择 |

### 6.3 使用分组

```java
@DataSource("slaves")  // 自动按 ROUND_ROBIN 策略选择 slave1 或 slave2
public List<Order> getOrders() {
    return orderMapper.selectAll();
}
```

### 6.4 连接失败重试

分组数据源内置 fallback 机制：选中成员连接失败时，自动尝试其余成员，全部失败才抛出异常。

---

## 7. 本地多数据源事务

`@LocalTransactional` 提供轻量级多数据源本地事务（Best-Efforts 1PC），不依赖 JTA，无需外部事务管理器。

### 7.1 基本用法

```java
@Service
public class TransferService {

    @Autowired
    private AccountMapper accountMapper;

    // 自动事务：在 master 和 slave（Groups）之外的所有数据源上开启事务
    @LocalTransactional
    public void transfer(Long fromId, Long toId, BigDecimal amount) {
        accountMapper.debit(fromId, amount);   // 数据源 A
        accountMapper.credit(toId, amount);     // 数据源 B
        // 全部成功 → 提交所有；任一失败 → 回滚所有
    }
}
```

### 7.2 手动指定数据源

```java
// 只在指定数据源上开启事务
@LocalTransactional({"master", "report"})
public void syncData() {
    masterMapper.sync();
    reportMapper.sync();
}
```

**注意事项：**
- 自动跳过 `GroupDataSource`（分组负载均衡）和未初始化的 `LazyDataSourceProxy`
- 遵循 Best-Efforts 1PC：逐一提交，第一个失败时其余尝试回滚，但不保证完整原子性
- 适用于跨库写操作的最终一致场景，强一致性需求请使用分布式事务方案

---

## 8. 健康检查

> 需引入 `spring-boot-starter-actuator`。

访问 `/actuator/health` 查看数据源健康状态：

```json
{
  "status": "UP",
  "components": {
    "dynamicDataSource": {
      "status": "UP",
      "details": {
        "master": "UP",
        "slave": "UP",
        "archive": "LAZY (not yet initialized)"
      }
    }
  }
}
```

**状态说明：**

| 状态 | 含义 |
|------|------|
| `UP` | 全部数据源可用 |
| `DEGRADED` | 部分数据源不可用 |
| `DOWN` | 全部数据源不可用 |
| `UNKNOWN` | 全部为 LAZY 且尚未初始化 |

---

## 9. SQL 日志拦截器

> 需引入 MyBatis。用于排查数据源切换是否正确。

```yaml
dp:
  datasource:
    sql-log-enabled: true
```

开启后，每次 MyBatis 执行 SQL 时输出 DEBUG 日志：

```
[DataSourceSqlLog] 当前数据源：slave，Mapper 方法：com.example.mapper.OrderMapper.selectRecent
```

> **注意**：仅输出 DEBUG 级别日志，需配合 `logging.level` 调整日志级别。

---

## 10. 完整实战示例

以电商系统的订单服务为例，展示 **主从读写分离 + 分组负载均衡 + 懒加载报表库**：

### 10.1 配置

```yaml
# application.yml
dp:
  datasource:
    primary: master
    connection-validation-enabled: true
    sql-log-enabled: false            # 生产环境建议关闭

    datasources:
      master:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://master-db:3306/shop
        username: root
        password: ${DB_PASSWORD}
        druid:
          initial-size: 5
          max-active: 20
          min-idle: 2

      slave1:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://slave1-db:3306/shop
        username: root
        password: ${DB_PASSWORD}
        druid:
          initial-size: 3
          max-active: 10

      slave2:
        type: com.alibaba.druid.pool.DruidDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://slave2-db:3306/shop
        username: root
        password: ${DB_PASSWORD}
        druid:
          initial-size: 3
          max-active: 10

      report:
        lazy: true                    # 报表库不常用，懒加载
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://report-db:3306/report
        username: root
        password: ${DB_PASSWORD}
        maximum-pool-size: 5

    groups:
      slaves:
        datasources: [slave1, slave2]
        strategy: ROUND_ROBIN
```

### 10.2 Service 实现

```java
@Service
public class OrderService {

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private ReportMapper reportMapper;

    // ========== 读操作：使用从库组（自动轮询） ==========

    @DataSource("slaves")
    public List<Order> getUserOrders(Long userId) {
        return orderMapper.selectByUserId(userId);
    }

    @DataSource("slaves")
    public Order getOrderDetail(Long orderId) {
        return orderMapper.selectById(orderId);
    }

    // ========== 写操作：使用主库（默认） ==========

    public void createOrder(Order order) {
        orderMapper.insert(order);
    }

    public void updateOrderStatus(Long orderId, String status) {
        orderMapper.updateStatus(orderId, status);
    }

    // ========== 批量操作：本地事务 ==========

    @LocalTransactional
    public void batchConfirmOrders(List<Long> orderIds) {
        for (Long id : orderIds) {
            orderMapper.updateStatus(id, "CONFIRMED");
        }
        // 所有 update 在一个事务中
    }

    // ========== 跨库操作：多数据源事务 ==========

    @LocalTransactional({"master", "report"})
    public void syncToReport(Long orderId) {
        Order order = orderMapper.selectById(orderId);
        reportMapper.upsert(order);
        // master 和 report 两个库的事务同时提交或回滚
    }

    // ========== 多租户：SpEL 动态数据源 ==========

    @DataSource("#tenantId")
    public List<Order> getTenantOrders(String tenantId) {
        return orderMapper.selectByTenant(tenantId);
    }
}
```

### 10.3 Controller

```java
@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    // GET /orders/user/123 → 自动走从库
    @GetMapping("/user/{userId}")
    public Result<List<Order>> userOrders(@PathVariable Long userId) {
        return Result.ok(orderService.getUserOrders(userId));
    }

    // POST /orders → 自动走主库
    @PostMapping
    public Result<Order> create(@RequestBody Order order) {
        orderService.createOrder(order);
        return Result.ok(order);
    }

    // POST /orders/batch-confirm → 主库事务
    @PostMapping("/batch-confirm")
    public Result<Void> batchConfirm(@RequestBody List<Long> orderIds) {
        orderService.batchConfirmOrders(orderIds);
        return Result.ok();
    }

    // GET /orders/tenant/tenant_a → SpEL 动态路由到 tenant_a
    @GetMapping("/tenant/{tenantId}")
    public Result<List<Order>> tenantOrders(@PathVariable String tenantId) {
        return Result.ok(orderService.getTenantOrders(tenantId));
    }
}
```

---

## 11. 配置参考

```yaml
dp:
  datasource:
    # ─── 基础配置 ───
    primary: master                                        # 主库 key
    connection-validation-enabled: true                    # 启动时验证连接
    sql-log-enabled: false                                 # SQL 日志拦截器
    map-underscore-to-camel-case: true                     # MyBatis 驼峰转换
    mapper-locations:                                      # Mapper XML 路径
      - classpath*:mapper/**/*.xml

    # ─── 数据源定义 ───
    datasources:
      myds:
        # 通用属性
        lazy: false                                        # 是否懒加载
        type: com.alibaba.druid.pool.DruidDataSource       # 连接池类型
        driver-class-name: com.mysql.cj.jdbc.Driver
        url: jdbc:mysql://host:3306/db                     # Druid: url
        # jdbc-url: jdbc:mysql://host:3306/db              # HikariCP: jdbc-url
        username: root
        password: 123456
        # Druid 专用属性
        druid:
          initial-size: 5
          max-active: 20
          min-idle: 2
          max-wait: 60000
        # HikariCP 专用属性
        # maximum-pool-size: 20
        # minimum-idle: 2
        # connection-timeout: 30000

    # ─── 分组（读写分离 / 负载均衡） ───
    groups:
      slaves:
        datasources: [slave1, slave2]
        strategy: ROUND_ROBIN                              # ROUND_ROBIN | RANDOM
```

---

## 12. 架构说明

### 12.1 MyBatis 拦截器注册机制

ddss 采用 **后补模式** 注册 MyBatis 拦截器（如 PageHelper 的 `PageInterceptor`）：

1. `SqlSessionFactory` 创建时不急于收集拦截器
2. 所有 Spring Bean 就绪后，通过 `SmartInitializingSingleton` 回调
3. 从容器中获取全部 `Interceptor` Bean，通过 `Configuration.addInterceptor()` 统一追加

**为什么需要后补模式？**

PageHelper 等第三方拦截器的自动配置类通常带有 `@ConditionalOnBean(SqlSessionFactory.class)`——也就是说，它们要等 `SqlSessionFactory` 创建后才激活。如果 ddss 在创建 `SqlSessionFactory` 时急切收集拦截器，正好陷入鸡-蛋死锁：

> ddss 收集拦截器时 PageInterceptor 还不存在 → PageHelper 激活时 SqlSessionFactory 已定稿 → 拦截器永远无法注入

**后补模式彻底消除了这个时序问题，对所有带 `@ConditionalOnBean(SqlSessionFactory.class)` 的拦截器自动兼容。**

### 12.2 涉及数据源的依赖链条

```
dp.datasource.datasources.* (YAML 配置)
  → DynamicDataSourceAutoConfiguration: 创建 DynamicDataSource + 各组 DataSource
  → DynamicDataSourceMyBatisAutoConfiguration: 创建 SqlSessionFactory（不急于设置插件）
  → SmartInitializingSingleton: 统一后补所有 Interceptor
  → SqlSessionTemplate: 基于 SqlSessionFactory 创建
```

---

## 13. 常见问题

### Q1: 注解不生效？

检查：
1. `@DataSource` 标注的方法必须通过 Spring Bean 调用（AOP 代理）
2. 不能是 `private` 方法
3. 不能是同类内部调用（this.xxx()），需要注入自身或拆分到不同 Service

```java
// ❌ 错误：同类内部调用不走代理
public void outer() {
    this.inner();  // @DataSource 不生效
}

@DataSource("slave")
public void inner() { ... }

// ✅ 正确：注入自身
@Service
public class MyService {
    @Autowired
    private MyService self;

    public void outer() {
        self.inner();  // @DataSource 生效
    }
}
```

### Q2: @LocalTransactional 和 Spring @Transactional 能用在一起吗？

不建议。`@LocalTransactional` 使用的是 `DataSourceTransactionManager`，如果方法上同时有 Spring 的 `@Transactional`，可能造成事务管理器冲突。选择其一使用。

### Q3: 懒加载的数据源健康检查没初始化怎么办？

正常。懒加载数据源的健康状态会显示 `LAZY (not yet initialized)`，不会主动创建连接池。只有被 `@DataSource` 访问时才会初始化。

### Q4: 启动时连接验证失败？

每条数据源在创建后会尝试获取连接（除非 `connection-validation-enabled: false`）。验证失败的典型原因：
- 数据库地址/端口错误
- 用户名密码错误
- 网络不通
- MySQL 驱动版本不匹配

可以通过设置 `connection-validation-enabled: false` 跳过启动验证（适合数据库启动顺序不可控的场景）。

### Q5: 分组健康检查结果怎么看？

`GroupDataSource` 的健康状态委托给第一个成员，连接获取失败会 fallback 到其他成员。健康检查本身不会触发 retry 逻辑，仅检查首成员。

### Q6: 支持编程式切换吗？

支持。可以直接使用静态 API（主要用于测试和非 AOP 场景）：

```java
// 切换到 slave
DynamicDataSource.setDataSource("slave");
try {
    // 业务代码
} finally {
    // 恢复主库（pop）
    DynamicDataSource.clearDataSource();
}

// 获取当前数据源
String current = DynamicDataSource.getDataSource();
```

> **注意**：在 Spring 管理的 Service 中优先使用 `@DataSource` 注解，更安全。

---

## 写在最后

ddss 追求 **简洁实用**，约定大于配置，开箱即用。如果你在使用中遇到问题或有功能建议，欢迎提 Issue。
