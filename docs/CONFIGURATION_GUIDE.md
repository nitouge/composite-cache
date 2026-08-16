# Composite Cache 配置使用指南

## 📋 目录

- [快速开始](#快速开始)
- [配置说明](#配置说明)
- [缓存难题 → 对应配置](#缓存难题--对应配置)
- [Key生成器配置](#key生成器配置)
- [注解使用](#注解使用)
- [监控统计](#监控统计)
- [最佳实践](#最佳实践)

---

## 🚀 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.nitouge</groupId>
    <artifactId>composite-cache-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- L2 必需：Redisson（无 RedissonClient 时自动降级为仅 L1） -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.23.5</version>
</dependency>
```

### 2. 配置文件

**application.yml**
```yaml
composite-cache:
  enabled: true
  key-generator-strategy: CUSTOM  # 仅注解未配 keyExpr 时作为回退
  metrics:
    enabled: true                 # 需 classpath 存在 MeterRegistry 才生效
```

### 3. 使用注解

```java
@Service
public class UserService {
    
    @CacheAble(cacheName = "user", keyExpr = "#id")
    public User getUserById(Long id) {
        return userDao.selectById(id);
    }
}
```

---

## ⚙️ 配置说明

### 完整配置示例

以下是一个包含所有配置项的完整示例（带注释和默认值），可直接复制到 `application.yml` 中按需修改：

```yaml
# ============================================================================
# Composite Cache 完整配置示例（带注释 + 默认值）
# ----------------------------------------------------------------------------
# 说明：
#   1. 绝大多数项都有合理默认值，最简接入只需 `composite-cache.enabled: true` + 一个 RedissonClient。
#   2. 标注【opt-in】的为按需开启的增强能力，默认关闭，不影响默认行为。
#   3. L2（二级缓存）基于 Redisson 实现，需 classpath 存在 RedissonClient（见下方 Redis 接入）。
#   4. 单项缓存（cacheName 维度）的 TTL/容量等更适合用注解 @Cache_L1/@Cache_L2 指定；
#      这里的 config.* 是全局默认/行为开关。
# ============================================================================

server:
  port: 8080

spring:
  application:
    name: my-app

  # --- Redis 接入（提供 RedissonClient）---
  # 引入 redisson-spring-boot-starter 后，下面的 spring.redis.* 会被用来自动创建 RedissonClient，
  # composite-cache 的 L2 即基于该 RedissonClient。（也可自行声明 RedissonClient @Bean）
  redis:
    host: localhost
    port: 6379
    password:
    database: 0
    timeout: 3000ms

# ============================================================================
# Composite Cache
# ============================================================================
composite-cache:
  # 总开关：true 才装配（引入 starter 后配置此项即自动生效，无需任何 @Enable 注解）
  enabled: true

  # Key 生成策略：DEFAULT / CUSTOM / SMART —— 仅在注解未配置 keyExpr 时作为回退兜底。
  # 推荐始终用注解的 keyExpr="#id"（与编程式业务 key 对齐，命中同一缓存项）。
  key-generator-strategy: CUSTOM

  # 监控（需 classpath 存在 MeterRegistry，即引入 actuator + micrometer-registry-xxx 才真正生效）
  metrics:
    enabled: true

  config:
    # ---------------- 基础 ----------------
    # instanceId 运行时自动设为 ip:port（多实例缓存同步标识），无需手填
    cache-mode: L1_L2            # L1 / L2 / L1_L2（默认 L1_L2；无 RedissonClient 时自动降级为 L1）
    dynamic: true               # 按 cacheName 动态创建缓存实例（默认 true）

    # ---------------- 防穿透：空值缓存（NullValue）----------------
    allow-null-values: true              # 缓存空值，防穿透（默认 true）
    null-value-expire-time-seconds: 60   # 空值过期时间（秒）
    null-value-max-size: 3000            # 空值最大数量（L1 本地，超出按淘汰）
    null-value-clear-period-seconds: 10  # 空值清理周期（秒）

    # ---------------- 组合缓存类型 ----------------
    composite:
      l1-cache-type: CAFFEINE   # L1：CAFFEINE（默认、推荐）/ GUAVA（可选）
      l2-cache-type: REDIS      # L2：REDIS（基于 Redisson）

    # ---------------- L1：Caffeine ----------------
    caffeine:
      manual-cache: false                   # false=LoadingCache（默认，提供单机单飞）；true=手动缓存
      auto-refresh-expire-cache: false      # 是否后台定时刷新过期缓存
      refresh-thread-pool-size: 4
      refresh-period: 30                    # 刷新周期（秒）
      publish-msg-period-milli-seconds: 500 # 同一 key 同步消息的最小发布间隔（毫秒）

    # ---------------- L1：Guava（仅当 l1-cache-type=GUAVA 时）----------------
    guava:
      manual-cache: false
      auto-refresh-expire-cache: false
      refresh-thread-pool-size: 4
      refresh-period: 30

    # ---------------- L2：Redis（Redisson）----------------
    redis:
      # 防击穿：L2 失效后的回源策略
      #   NONE           不加保护（性能最好；集群高并发同一 key 可能多次回源）
      #   LOCK           本地锁 + Redisson 分布式锁两级单飞（写少读多、强一致优先）
      #   LOGICAL_EXPIRE 逻辑过期 + 异步刷新（永不阻塞/永不击穿，可容忍短暂旧值）
      load-strategy: LOCK
      try-lock: true            # LOCK 下：true=抢不到锁快速失败，false=阻塞等待
      logical-expire-physical-ttl-factor: 2  # LOGICAL_EXPIRE 下：物理 TTL = 逻辑 TTL × 该倍数

      # 防雪崩：TTL 随机抖动，实际 TTL = base + rand(0, base*ratio)。0 关闭。
      ttl-jitter-ratio: 0.1

      user-prefix: true         # L2 key 是否以 cacheName 为前缀（true 才支持 clear()）
      support-batch: true       # 是否用 pipeline 批量操作
      batch-size: 50

      # 【opt-in】运行期 L2 降级（熔断）：Redis 抖动/不可用时读降级为未命中/回落 loader、写安全跳过，恢复自愈。
      # 注意：开启后会吞掉 L2 连接类异常（不再上抛），属语义变化，按需开启。
      degrade-enabled: false
      degrade-failure-threshold: 5      # 连续失败达阈值打开熔断
      degrade-open-millis: 10000        # 熔断打开持续时间（ms），到期半开试探
      degrade-max-concurrent-loads: 0   # 降级期间回源 DB 并发上限（0=不限）

    # ---------------- 跨节点 L1 同步（多实例必备）----------------
    cache-sync-policy:
      msg-type: REDIS           # REDIS / KAFKA（不配则不开启跨节点同步）
      topic: cache-sync-topic
      async: true               # 异步发送同步消息
      enhanced: true            # REDIS 默认用可靠版（版本门控防乱序 + 去重 + 失败补偿）；false 回退基础版
      enable-ack: false         # 可靠版是否启用 ACK 确认
      max-retries: 3            # 可靠版发布失败重试次数

    # ---------------- 一致性增强（按需 opt-in）----------------
    consistency:
      # 延迟双删：evict 后延迟再删一次，缓解主从/同步延迟脏数据
      delayed-double-delete: false
      delayed-double-delete-millis: 500
      # 删除失败补偿：L2 删除失败记账，后台定时重试（需 Redis）
      delete-compensation: false
      delete-compensation-max-retry: 10
      delete-compensation-interval-seconds: 60
      # 注册主从一致性处理器 Bean（编程式：写后短时强制读主）
      master-slave: false

    # ---------------- 防穿透增强：布隆过滤器（按需 opt-in）----------------
    # 注册布隆 Bean（默认本地 Guava）。注意：仍需对每个 cacheName 显式 warmUp 全量合法 key 才生效，
    # 否则未预热的合法 key 会被误拦。未注册的 cacheName 不受影响。
    penetration:
      bloom-enabled: false
      bloom-expected-insertions: 1000000
      bloom-fpp: 0.01

# ============================================================================
# （可选）Actuator + Prometheus（配合 composite-cache.metrics.enabled=true）
# ============================================================================
# compositecache 是本框架提供的缓存管理端点（需引入 actuator 并在此暴露后生效）：
#   GET    /actuator/compositecache          查看全局命中率与各缓存概览（类型/L1 条目数/命中统计）
#   GET    /actuator/compositecache/{name}   查看单个缓存详情
#   POST   /actuator/compositecache/{name}   body {"key":"xxx"} 逐出指定 key
#   DELETE /actuator/compositecache          清空所有缓存
#   DELETE /actuator/compositecache/{name}   清空指定缓存
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,compositecache

# ============================================================================
# 日志：命中链路追踪（数据来自 L1 / L2 / 回源 DB）
# ============================================================================
# 专用 logger io.github.nitouge.cache.access（与各实现类 logger 分离，可单独精准开关）。
# 每次读取输出一条来源结论：L1 命中 / L2 命中(回填L1) / 回源加载(回填L2/L1) / 未命中。
# 仅打印 cacheName 与 key，绝不打印缓存值（避免敏感数据）；DEBUG 默认关闭、对生产零影响。
# 想"只看链路"而不被其它 debug 淹没：包级设 INFO、单独开 access。
logging:
  level:
    io.github.nitouge.cache: INFO
    io.github.nitouge.cache.access: DEBUG    # 命中链路（L1/L2/回源）
```

### 最小配置（快速开始）

对于大多数场景，只需要配置以下几项即可快速开始使用：

```yaml
composite-cache:
  enabled: true                   # 总开关
  key-generator-strategy: CUSTOM  # 推荐使用 CUSTOM
  metrics:
    enabled: true

spring:
  redis:
    host: localhost
    port: 6379
```

### 核心配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `enabled` | Boolean | false | 是否启用缓存（总开关） |
| `key-generator-strategy` | String | CUSTOM | Key 生成策略（DEFAULT/CUSTOM/SMART），仅注解未配 keyExpr 时回退 |
| `metrics.enabled` | Boolean | true | 缓存监控（需 classpath 存在 MeterRegistry 才生效） |
| `config.cache-mode` | Enum | L1_L2 | 缓存模式 L1/L2/L1_L2 |
| `config.allow-null-values` | Boolean | true | 是否缓存 null 值（防穿透） |
| `config.null-value-expire-time-seconds` | Long | 60 | null 值过期时间（秒） |

> 💡 **提示**：上面的"完整配置示例"包含了所有可用的配置项和详细注释，可作为参考。大多数配置项都有合理的默认值，无需全部配置。

---

## 缓存难题 → 对应配置

| 难题 | 方案 | 关键配置（`composite-cache.config.*`） |
|---|---|---|
| 穿透 | 空值缓存 +（可选）布隆 | `allow-null-values=true`（默认）；`penetration.bloom-enabled=true`（需对 cacheName `warmUp` 全量合法 key） |
| 击穿 | 回源加锁 / 逻辑过期 | `redis.load-strategy=LOCK`（配 `try-lock`）或 `LOGICAL_EXPIRE` |
| 雪崩 | TTL 随机抖动 | `redis.ttl-jitter-ratio=0.1`（默认） |
| 一致性 | 先删 L2 再删 L1 + 跨节点广播 + 延迟双删 + 删除补偿 | `cache-sync-policy.msg-type=REDIS`（`enhanced=true` 默认可靠版）；`consistency.delayed-double-delete`、`consistency.delete-compensation` |
| Redis 抖动 | 运行期熔断降级 | `redis.degrade-enabled=true`（opt-in） |
| 多实例 L1 同步 | 失效广播 | `cache-sync-policy.msg-type=REDIS / KAFKA` |

> 增强一致性、运行期降级、布隆过滤器等均为 **opt-in（默认关闭）**，开启后才接入读写路径，不影响默认行为。各项含义与默认值详见 [`application-example.yml`](../temp/application-example.yml)。

---

## 🔑 Key生成器配置

### 三种策略对比

| 策略 | 格式 | 适用场景 | 示例 |
|------|------|----------|------|
| **DEFAULT** | 参数值 | 简单场景 | `123` |
| **CUSTOM** | ClassName:methodName:params | 多服务共用（推荐） | `UserService:getUserById:123` |
| **SMART** | ClassName:methodName:params或MD5 | 大对象场景 | `OrderService:createOrder:MD5:a1b2c3...` |

### 配置方式

#### 方式1：通过配置文件（推荐）

```yaml
composite-cache:
  key-generator-strategy: CUSTOM  # DEFAULT, CUSTOM, SMART
```

#### 方式2：自定义Bean

```java
@Configuration
public class CacheConfiguration {
    
    @Bean
    public CacheKeyGenerator cacheKeyGenerator() {
        // 使用工厂类
        return CacheKeyGeneratorFactory.getCustom();
        
        // 或使用智能生成器（自定义配置）
        // return CacheKeyGeneratorFactory.getSmart(300, 10);
    }
}
```

#### 方式3：自定义实现

```java
@Configuration
public class CacheConfiguration {
    
    @Bean
    public CacheKeyGenerator cacheKeyGenerator() {
        return new CacheKeyGenerator() {
            @Override
            public Object generate(Object keyValue) {
                // 自定义逻辑
                return "custom:" + keyValue;
            }
        };
    }
}
```

---

## 📝 注解使用

### 1. @CacheAble - 查询缓存

```java
@Service
public class UserService {
    
    // 基本用法
    @CacheAble(cacheName = "user", keyExpr = "#id")
    public User getUserById(Long id) {
        return userDao.selectById(id);
    }
    
    // 配置L1/L2参数
    @CacheAble(
        cacheName = "user",
        keyExpr = "#id",
        cacheL1 = @Cache_L1(TTL = 300, maximumSize = 1000),
        cacheL2 = @Cache_L2(TTL = 3600)
    )
    public User getUserWithConfig(Long id) {
        return userDao.selectById(id);
    }
    
    // 复杂表达式
    @CacheAble(
        cacheName = "user",
        keyExpr = "'user:' + #name + ':' + #age"
    )
    public User getUserByNameAndAge(String name, Integer age) {
        return userDao.selectByNameAndAge(name, age);
    }
}
```

### 2. @CachePut - 更新缓存

```java
@Service
public class UserService {
    
    @CachePut(cacheName = "user", keyExpr = "#user.id")
    public User updateUser(User user) {
        userDao.update(user);
        return user;
    }
    
    // 使用#result
    @CachePut(cacheName = "user", keyExpr = "#result.id")
    public User createUser(User user) {
        userDao.insert(user);
        return user;
    }
}
```

### 3. @CacheEvict - 删除缓存

```java
@Service
public class UserService {
    
    // 删除单个
    @CacheEvict(cacheName = "user", keyExpr = "#id")
    public void deleteUser(Long id) {
        userDao.deleteById(id);
    }
    
    // 删除所有
    @CacheEvict(cacheName = "user", removeAll = true)
    public void deleteAllUsers() {
        userDao.deleteAll();
    }
}
```

### 4. @BatchCacheAble - 批量查询

```java
@Service
public class UserService {
    
    @BatchCacheAble(
        cacheName = "user",
        keyExtractor = "#user.id"  // 从返回对象提取Key
    )
    public List<User> getUsersByIds(List<Long> ids) {
        return userDao.selectByIds(ids);
    }
}
```

### 5. @Caches - 组合注解

```java
@Service
public class UserService {
    
    @Caches(
        cacheAble = @CacheAble(cacheName = "user", keyExpr = "#id"),
        cachePut = @CachePut(cacheName = "userList", keyExpr = "'all'"),
        cacheEvict = @CacheEvict(cacheName = "userCount", removeAll = true)
    )
    public User updateAndRefresh(Long id) {
        return userDao.selectById(id);
    }
}
```

---

## 📊 监控统计

> 历史上的 `AnnotationMetrics` 等多套指标已统一为 `CacheMetricsRecorder`：命中/未命中由 `CompositeCache` 统一记录，注解与编程式共用同一口径，避免重复计数。

### 启用监控

```yaml
composite-cache:
  metrics:
    enabled: true        # 默认 true；需 classpath 存在 MeterRegistry 才真正生效

# 引入 actuator + micrometer registry，并暴露端点
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,prometheus,compositecache   # compositecache=缓存管理端点（见下）
```

> 依赖（按需）：`spring-boot-starter-actuator` + 一个 Micrometer registry（如 `micrometer-registry-prometheus`）。

### 指标项（Micrometer）

| 指标 | 说明 |
|------|------|
| `cache.hit` | 命中次数（按 `cache` / `level`=L1/L2 打 tag） |
| `cache.miss` | 未命中次数 |
| `cache.latency` | 操作耗时 |
| `cache.eviction` | 驱逐次数 |
| `cache.load` | 回源 DB 耗时（按成功/失败） |
| `cache.exception` | 操作异常（含 `l2-degraded` 降级事件） |

### 缓存管理端点（Actuator，id=compositecache）

引入 `spring-boot-starter-actuator` 并在 `management.endpoints.web.exposure.include` 暴露 `compositecache` 后生效，提供运维查看与操作：

| 方法 | 路径 | 作用 |
|------|------|------|
| GET | `/actuator/compositecache` | 全局命中率 + 各缓存概览（类型 / L1 条目数 / 命中统计） |
| GET | `/actuator/compositecache/{name}` | 单个缓存详情 |
| POST | `/actuator/compositecache/{name}` | 请求体 `{"key":"xxx"}`，逐出指定 key |
| DELETE | `/actuator/compositecache` | 清空所有缓存 |
| DELETE | `/actuator/compositecache/{name}` | 清空指定缓存 |

```bash
curl http://localhost:8080/actuator/compositecache
curl http://localhost:8080/actuator/compositecache/userCache
curl -X POST http://localhost:8080/actuator/compositecache/userCache \
     -H "Content-Type: application/json" -d '{"key":"user:1"}'
curl -X DELETE http://localhost:8080/actuator/compositecache/userCache
```

> 命中统计依赖 `metrics.enabled=true`（classpath 存在 MeterRegistry）；未启用时端点仍可用，只是不含 `stats` 字段。

### 命中链路日志（数据来自 L1/L2/回源）

专用 logger `io.github.nitouge.cache.access`，用于观察每次读取的数据来源（与各实现类 logger 分离，可单独精准开关、不被其它 debug 淹没）：

```yaml
logging:
  level:
    io.github.nitouge.cache: INFO            # 其它日志保持安静
    io.github.nitouge.cache.access: DEBUG    # 只看命中链路
```

开启后每次读取输出一条来源结论：

```text
[cache:user] key=1 <- L1(本地) 命中
[cache:user] key=2 <- L2(远程) 命中，回填 L1
[cache:user] key=3 <- 回源加载(数据源)：L2 未命中，策略=LOCK，将回填 L2/L1
```

> - 只打印 `cacheName` 与 `key`，**绝不打印缓存值**（避免敏感数据进入日志）；若 key 本身敏感，请勿开启本 logger。
> - DEBUG 级、默认关闭，内部有 `isDebugEnabled` 守卫，对生产读写路径零影响。

### 查看统计（内存聚合）

启用监控后会注册 `CacheStatisticsAggregator`（定期日志上报全局/分缓存命中率），也可注入它编程式读取：

```java
@Service
public class CacheMonitorService {

    @Autowired
    private CacheStatisticsAggregator aggregator;  // metrics.enabled=true 且存在 MeterRegistry 时可用

    public void printStats() {
        CacheStatisticsAggregator.GlobalCacheStats global = aggregator.getGlobalStats();
        log.info("global hitRate={}, total={}", global.getHitRate(), global.getTotalRequests());
        aggregator.getAllStats().forEach((name, s) ->
                log.info("cache={}, hit={}, miss={}, hitRate={}",
                        name, s.getHitCount(), s.getMissCount(), s.getHitRate()));
    }
}
```

---

## 💡 最佳实践

### 1. Key生成器选择

```yaml
# 开发环境：使用DEFAULT（简单快速）
composite-cache:
  key-generator-strategy: DEFAULT

# 测试环境：使用CUSTOM（避免冲突）
composite-cache:
  key-generator-strategy: CUSTOM

# 生产环境：使用SMART（处理大对象）
composite-cache:
  key-generator-strategy: SMART
```

### 2. 缓存命名规范

```java
// ✅ 推荐：使用业务领域命名
@CacheAble(cacheName = "user", keyExpr = "#id")
@CacheAble(cacheName = "product", keyExpr = "#id")
@CacheAble(cacheName = "order", keyExpr = "#id")

// ❌ 不推荐：使用通用名称
@CacheAble(cacheName = "cache", keyExpr = "#id")
@CacheAble(cacheName = "data", keyExpr = "#id")
```

### 3. Key表达式规范

```java
// ✅ 推荐：简洁明了
@CacheAble(cacheName = "user", keyExpr = "#id")
@CacheAble(cacheName = "user", keyExpr = "#user.id")
@CacheAble(cacheName = "user", keyExpr = "'user:' + #id")

// ❌ 不推荐：过于复杂
@CacheAble(cacheName = "user", keyExpr = "#user.id + ':' + #user.name + ':' + #user.age")
```

### 4. 监控统计使用

启用 `composite-cache.metrics.enabled=true`（并引入 Micrometer/Actuator）后：

- Prometheus 直接抓取 `cache.hit`/`cache.miss`/`cache.latency` 等指标（见上文「监控统计」）；
- `CacheStatisticsAggregator` 会定期日志上报命中率，也可注入它编程式读取（见上文示例）。

无需再自行维护 `AnnotationMetrics`（已统一为 `CacheMetricsRecorder`）。

### 5. 异常处理

```java
@Service
public class UserService {
    
    // ✅ 推荐：启用异常忽略（生产环境）
    @CacheAble(
        cacheName = "user",
        keyExpr = "#id",
        ignoreException = true  // 缓存失败时降级到方法执行
    )
    public User getUserById(Long id) {
        return userDao.selectById(id);
    }
    
    // ❌ 不推荐：不忽略异常（开发环境可用）
    @CacheAble(
        cacheName = "user",
        keyExpr = "#id",
        ignoreException = false  // 缓存失败时抛出异常
    )
    public User getUserByIdStrict(Long id) {
        return userDao.selectById(id);
    }
}
```

---

## 🔧 高级配置

### 1. 多环境配置

**application.yml**（Spring Boot 2.4+ 多文档 profile 写法）
```yaml
---
# 开发环境
spring:
  config:
    activate:
      on-profile: dev
composite-cache:
  enabled: true
  key-generator-strategy: CUSTOM
  config:
    cache-mode: L1            # 开发环境可仅用本地缓存

---
# 生产环境
spring:
  config:
    activate:
      on-profile: prod
composite-cache:
  enabled: true
  key-generator-strategy: SMART
  metrics:
    enabled: true
  config:
    cache-mode: L1_L2
    null-value-expire-time-seconds: 300
    redis:
      load-strategy: LOCK      # 防击穿
      ttl-jitter-ratio: 0.1    # 防雪崩
```

### 2. 自定义Key生成器（多租户场景）

```java
@Configuration
public class TenantCacheConfiguration {
    
    @Bean
    public CacheKeyGenerator tenantCacheKeyGenerator() {
        return new CacheKeyGenerator() {
            
            @Autowired
            private TenantContext tenantContext;
            
            @Override
            public Object generate(Object keyValue) {
                String tenantId = tenantContext.getCurrentTenantId();
                return "tenant:" + tenantId + ":" + keyValue;
            }
        };
    }
}
```

### 3. 条件化配置

```java
@Configuration
@ConditionalOnProperty(name = "composite-cache.custom.enabled", havingValue = "true")
public class CustomCacheConfiguration {
    
    @Bean
    public CacheKeyGenerator customKeyGenerator() {
        return CacheKeyGeneratorFactory.getSmart(500, 10);
    }
}
```

---

## 📚 相关文档

- [README](../README.md) — 项目总览与接入
- [STARTER_GUIDE](../temp/STARTER_GUIDE.md) — Starter 详细使用指南
- [QUICK_START](../QUICK_START.md) — 5 分钟上手
- [KEY_GENERATOR_GUIDE](../temp/KEY_GENERATOR_GUIDE.md) — Key 生成器指南

---

**文档版本**: 1.0.0-SNAPSHOT  
**最后更新**: 2026-08-16
