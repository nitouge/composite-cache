# 🚀 Composite Cache - 快速开始指南

## 5分钟快速上手

### 第一步：添加依赖

在你的 Spring Boot 项目的 `pom.xml` 中添加：

```xml
<dependency>
    <groupId>io.github.nitouge</groupId>
    <artifactId>composite-cache-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- L2 必需：Redisson（版本与你的 Spring Boot 兼容即可，本项目基于 3.23.5 验证） -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.23.5</version>
</dependency>
```

> L2 二级缓存基于 Redisson；无 RedissonClient 时框架自动降级为仅 L1。

### 第二步：配置 Redis

在 `application.yml` 中添加：

```yaml
spring:
  redis:
    host: localhost
    port: 6379

composite-cache:
  enabled: true
```

### 第三步：启用缓存

配置 `composite-cache.enabled=true` 即自动装配（基于 `AutoConfiguration.imports`，兼容旧版 `spring.factories`），**无需 `@Enable` 注解**：

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 第四步：使用缓存

#### 方式一：使用注解（推荐用于简单场景）

```java
@Service
public class UserService {
    
    @CacheAble(cacheName = "user", keyExpr = "#id")
    public User getUserById(Long id) {
        // 这个方法只会在缓存未命中时执行
        return database.findById(id);
    }
    
    @CachePut(cacheName = "user", keyExpr = "#user.id")
    public User updateUser(User user) {
        database.save(user);
        return user;  // 返回值会更新到缓存
    }
    
    @CacheEvict(cacheName = "user", keyExpr = "#id")
    public void deleteUser(Long id) {
        database.deleteById(id);
    }
}
```

#### 方式二：编程式 API（推荐用于复杂/批量/自调用场景）

`CacheTemplate`/`CacheService` 与注解同源，处理注解表达不了的场景（自调用不过 AOP、动态 cacheName、批量部分命中回源等）：

```java
@Service
public class ProductService {

    @Autowired
    private CacheTemplate cacheTemplate;

    // 单键：未命中则回源并写回
    public Product getProduct(Long id) {
        return cacheTemplate.getOrLoad("product", id, () -> productMapper.selectById(id));
    }

    // 批量：仅对未命中子集回源（按入参顺序返回）
    public List<Product> getProducts(List<Long> ids) {
        return cacheTemplate.batchGetOrLoadList(
                "product", ids, Product::getId,
                missIds -> productMapper.selectBatchIds(missIds));
    }
}
```

> 如需直接操作缓存（任意复杂类型 List/Map/自定义对象），可注入底层 `CacheManager`：
> `cacheManager.getCache("name").put(key, value)` / `.get(key)` / `.batchGet(keys)`。

## 🧪 测试效果

### 运行测试项目

```bash
# 1. 启动 Redis
docker run -d -p 6379:6379 redis

# 2. 进入测试项目目录
cd composite-cache-test

# 3. 启动应用
mvn spring-boot:run
```

### 测试 API

```bash
# 第一次查询（慢，约1秒）
curl http://localhost:8080/api/users/1

# 第二次查询（快，约5毫秒）
curl http://localhost:8080/api/users/1

# 批量查询
curl -X POST http://localhost:8080/api/users/cache/batch-test \
  -H "Content-Type: application/json" \
  -d '[1,2,3,4,5]'
```

你会看到：
- 第一次查询响应时间：~1000ms
- 第二次查询响应时间：~5ms
- **性能提升 200倍！**

## 📝 常用注解参数

### @CacheAble

```java
@CacheAble(
    cacheName = "user",              // 缓存名称（必填）
    keyExpr = "#id",                 // 缓存Key（支持SpEL）
    cacheMode = CacheModeEnum.L1_L2, // L1, L2, L1_L2
    cacheL1 = @Cache_L1(
        TTL = 3600,                  // 过期时间（秒）
        maximumSize = 1000           // 最大容量
    ),
    cacheL2 = @Cache_L2(
        TTL = 86400                  // 过期时间（秒）
    )
)
```

### SpEL 表达式示例

```java
// 使用参数
@CacheAble(keyExpr = "#id")
public User getUser(Long id) { }

// 使用对象属性
@CacheAble(keyExpr = "#user.id")
public User save(User user) { }

// 字符串拼接
@CacheAble(keyExpr = "'user:' + #id")
public User getUser(Long id) { }

// 组合表达式
@CacheAble(keyExpr = "#type + ':' + #id")
public User getUser(String type, Long id) { }
```

## ⚙️ 常用配置

> 完整配置（防击穿 `redis.load-strategy`、防雪崩 `redis.ttl-jitter-ratio`、运行期降级 `redis.degrade-*`、可靠同步 `cache-sync-policy.enhanced`、一致性 `consistency.*`、布隆 `penetration.*` 等）见 [`application-example.yml`](temp/application-example.yml)。

### 基础配置

```yaml
composite-cache:
  enabled: true
  config:
    cache-mode: L1_L2              # 缓存模式
    allow-null-values: true        # 允许缓存null值（防止缓存穿透）
```

### 选择 L1 缓存类型

```yaml
composite-cache:
  config:
    composite:
      l1-cache-type: CAFFEINE      # CAFFEINE（推荐）或 GUAVA
      l2-cache-type: REDIS
```

### 调整缓存大小和过期时间

```yaml
composite-cache:
  config:
    caffeine:
      refresh-period: 30           # 刷新频率（秒）
    redis:
      support-batch: true          # 启用批量操作
      batch-size: 50               # 批量大小
```

## 💡 最佳实践

### 1. 选择合适的缓存模式

- **L1_L2**（推荐）：最佳性能，适合高并发场景
- **L2**：适合多实例共享数据
- **L1**：适合单实例、数据不共享的场景

### 2. 合理设置过期时间

```java
@CacheAble(
    cacheName = "user",
    keyExpr = "#id",
    cacheL1 = @Cache_L1(TTL = 300),    // L1: 5分钟
    cacheL2 = @Cache_L2(TTL = 3600)    // L2: 1小时
)
```

### 3. 使用批量操作提升性能

```java
// ❌ 不推荐：逐个查询
for (Long id : ids) {
    cache.get(id);
}

// ✅ 推荐：批量查询
Map<Long, User> users = cache.batchGet(ids);
```

### 4. 缓存复杂类型

```java
// 缓存 List
cache.put("userList", Arrays.asList(user1, user2, user3));

// 缓存 Map
cache.put("userMap", userMap);

// 缓存自定义对象
cache.put("config", new AppConfig());
```

## 🔍 常见问题

### Q1: 缓存不生效？

检查：
1. 配置文件中 `composite-cache.enabled: true`（启用的关键，无需 `@Enable` 注解）
2. L2 需要 RedissonClient（引入 `redisson-spring-boot-starter` 并配置 `spring.redis.*`），否则只有 L1
3. Redis 是否正常运行

### Q2: 如何清空所有缓存？

```java
@CacheEvict(cacheName = "user", removeAll = true)
public void clearAll() {
    // 清空 user 缓存的所有数据
}
```

### Q3: 如何缓存 null 值？

```yaml
composite-cache:
  config:
    allow-null-values: true  # 启用null值缓存
```

### Q4: 如何查看缓存统计？

```java
@Autowired
private CacheManager cacheManager;

public void showStats() {
    Collection<String> cacheNames = cacheManager.getCacheNames();
    System.out.println("缓存列表: " + cacheNames);
}
```

## 📚 更多资源

- **完整文档**：查看 [README.md](README.md)
- **详细指南**：查看 [STARTER_GUIDE.md](STARTER_GUIDE.md)
- **变更日志**：查看 [CHANGELOG.md](CHANGELOG.md)
- **开发路线**：查看 [ROADMAP.md](ROADMAP.md)

## 🎯 下一步

1. ✅ 在你的项目中集成 Composite Cache
2. ✅ 运行测试项目，体验缓存效果
3. ✅ 根据业务需求调整配置
4. ✅ 使用编程式API处理复杂场景

---

**开始享受高性能缓存吧！** 🚀

有问题？查看完整文档或提交 Issue。
