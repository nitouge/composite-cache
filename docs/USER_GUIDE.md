# Composite Cache 用户指南

本文档详细介绍 Composite Cache 的使用方法，包括注解方式和编程式 API。

## 目录

- [快速开始](#快速开始)
- [注解方式使用](#注解方式使用)
- [编程式 API](#编程式-api)
- [SpEL 表达式](#spel-表达式)
- [CacheManager 底层 API](#cachemanager-底层-api)
- [批量操作](#批量操作)
- [高级特性](#高级特性)

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.github.nitouge</groupId>
    <artifactId>composite-cache-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- L2 必需：Redisson（提供 RedissonClient；版本与你的 Spring Boot 兼容即可，本项目基于 3.23.5 验证） -->
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
    <version>3.23.5</version>
</dependency>
```

> L2 二级缓存基于 **Redisson** 实现（分布式锁、RTopic 广播、RBatch 管道、布隆等均依赖它）。仅引入 `spring-boot-starter-data-redis`/`RedisTemplate` 不足以启用 L2；无 RedissonClient 时框架自动降级为仅 L1。

### 2. 配置文件

```yaml
# application.yml
spring:
  redis:
    host: localhost
    port: 6379

composite-cache:
  enabled: true
  config:
    allow-null-values: true
    cache-mode: L1_L2  # L1, L2, L1_L2
    composite:
      l1-cache-type: CAFFEINE  # CAFFEINE, GUAVA
      l2-cache-type: REDIS
```

### 3. 启用缓存

引入 starter 后，只需配置总开关 `composite-cache.enabled=true` 即自动装配，**无需任何 `@Enable` 注解**：

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

> 自动装配通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（并兼容旧版 `spring.factories`）完成。

## 注解方式使用

### @CacheAble - 查询缓存

用于查询方法，先从缓存获取，缓存不存在则执行方法并缓存结果。

```java
@Service
public class UserService {
    
    @CacheAble(
        cacheName = "user",
        keyExpr = "#id",
        cacheMode = CacheModeEnum.L1_L2
    )
    public User getUserById(Long id) {
        return userRepository.findById(id);
    }
}
```

**完整参数说明：**

```java
@CacheAble(
    cacheName = "user",           // 缓存名称（必填）
    keyExpr = "#id",              // 缓存Key表达式（支持SpEL）
    cacheMode = CacheModeEnum.L1_L2,  // 缓存模式
    isAsync = false,              // 是否异步
    ignoreException = true,       // 是否忽略异常
    cacheL1 = @Cache_L1(          // L1缓存配置
        initialCapacity = 100,
        maximumSize = 1000,
        TTL = 3600,
        timeUnit = TimeUnit.SECONDS,
        expireMode = CacheExpireModeEnum.WRITE
    ),
    cacheL2 = @Cache_L2(          // L2缓存配置
        TTL = 86400,
        timeUnit = TimeUnit.SECONDS,
        dataType = CacheDataTypeEnum.DATA_TYPE_STRING
    )
)
```

### @CachePut - 更新缓存

用于更新方法，执行方法后更新缓存。

```java
@CachePut(
    cacheName = "user",
    keyExpr = "#user.id"
)
public User updateUser(User user) {
    return userRepository.save(user);
}
```

### @CacheEvict - 删除缓存

用于删除缓存。

```java
// 删除指定key
@CacheEvict(
    cacheName = "user",
    keyExpr = "#id"
)
public void deleteUser(Long id) {
    userRepository.deleteById(id);
}

// 清空所有缓存
@CacheEvict(
    cacheName = "user",
    removeAll = true
)
public void clearAllCache() {
    // ...
}
```

## 编程式 API

> 注解适合简单声明式场景；**自调用（同类内部调用不过 AOP）、动态 cacheName/TTL、批量部分命中回源、复杂一致性编排**等用编程式。`CacheTemplate`/`CacheService` 与注解**同源**（同一套 key、命中同一缓存项），底层自动享有防穿透/防击穿/降级等能力。

### 方式一：CacheTemplate（单键 + 批量门面）

```java
@Service
public class ProductQueryService {

    @Autowired
    private CacheTemplate cacheTemplate;

    // 单键：未命中则回源并写回
    public Product getProduct(Long id) {
        return cacheTemplate.getOrLoad("product", id, () -> productMapper.selectById(id));
    }

    // 批量：仅对未命中子集回源（按入参顺序返回 List）
    public List<Product> getProducts(List<Long> ids) {
        return cacheTemplate.batchGetOrLoadList(
                "product", ids, Product::getId,
                missIds -> productMapper.selectBatchIds(missIds));
    }

    // 更新：先更 DB 再删缓存
    public void update(Product p) {
        productMapper.updateById(p);
        cacheTemplate.evict("product", p.getId());
    }
}
```

### 方式二：CacheService<K, R>（业务维度类型化门面）

```java
@Service
public class ProductCacheService implements CacheService<Long, Product> {

    @Autowired private CacheTemplate cacheTemplate;
    @Autowired private ProductMapper productMapper;

    @Override public CacheTemplate getCacheTemplate() { return cacheTemplate; }
    @Override public String getCacheName() { return "product"; }

    @Override public Product queryData(Long id) { return productMapper.selectById(id); }
    @Override public Map<Long, Product> queryDataList(List<Long> ids) {
        return productMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
    }
    @Override public void updateData(Long id, Product p) { productMapper.updateById(p); }
    
    // 即可使用：getOrLoad / batchGetOrLoad / put / evict / update(先更DB再删缓存) 等
}
```

**使用示例：**

```java
@Autowired
private ProductCacheService productCacheService;

// 查询单个（带缓存）
Product p = productCacheService.getOrLoad(1L);

// 批量查询（部分命中回源）
List<Product> products = productCacheService.batchGetOrLoad(Arrays.asList(1L, 2L, 3L));

// 更新（先更DB再删缓存）
productCacheService.update(1L, updatedProduct);
```

## SpEL 表达式

支持在 `keyExpr` 中使用 SpEL 表达式：

```java
// 使用方法参数
@CacheAble(keyExpr = "#id")
public User getUser(Long id) { }

// 使用对象属性
@CacheAble(keyExpr = "#user.id")
public User saveUser(User user) { }

// 字符串拼接
@CacheAble(keyExpr = "'user:' + #id")
public User getUser(Long id) { }

// 复杂表达式
@CacheAble(keyExpr = "#user.id + ':' + #user.type")
public User getUser(User user) { }
```

## CacheManager 底层 API

`CacheManager` 提供了最底层的缓存操作接口，支持任意复杂类型如 `List<>`。

### 基本操作

```java
@Service
public class ProductService {
    
    @Autowired
    private CacheManager cacheManager;
    
    public void basicOperations() {
        Cache cache = cacheManager.getCache("product");
        
        // 基本操作
        cache.put(1L, product);
        Product p = (Product) cache.get(1L);
        cache.evict(1L);
        cache.clear();
        boolean exists = cache.isExists(1L);
        
        // 带加载器的获取
        Product loaded = (Product) cache.get(1L, () -> loadFromDb(1L));
    }
}
```

### 批量操作

```java
public List<Product> getProducts(List<Long> ids) {
    Cache cache = cacheManager.getCache("product");
    
    // 批量获取
    Map<Long, Product> cached = cache.batchGet(ids);
    
    // 查找未命中的ID
    List<Long> missedIds = ids.stream()
        .filter(id -> !cached.containsKey(id))
        .collect(Collectors.toList());
    
    // 从数据库加载未命中的数据
    if (!missedIds.isEmpty()) {
        List<Product> fromDb = productRepository.findByIds(missedIds);
        
        // 批量设置到缓存
        Map<Long, Product> toCache = fromDb.stream()
            .collect(Collectors.toMap(Product::getId, p -> p));
        cache.batchPut(toCache, Product::getId);
        
        cached.putAll(toCache);
    }
    
    return new ArrayList<>(cached.values());
}
```

### 缓存任意复杂类型

```java
// 缓存 List
public void cacheList(String key, List<User> users) {
    Cache cache = cacheManager.getCache("userList");
    cache.put(key, users);  // 支持任意类型
}

public List<User> getCachedList(String key) {
    Cache cache = cacheManager.getCache("userList");
    return (List<User>) cache.get(key);
}
```

## 批量操作

### 批量操作优化

使用批量API可以显著提升性能：

```java
// ❌ 传统方式（N次网络请求）
List<User> users = new ArrayList<>();
for (Long id : ids) {
    users.add(cache.get(id));
}

// ✅ 批量方式（1次网络请求）
Map<Long, User> users = cache.batchGet(ids);
```

### 批量获取或加载

```java
// 批量获取，未命中的自动回源
Map<Long, Product> result = cache.batchGetOrLoad(
    keyList,
    missedKeys -> loadFromDb(missedKeys)
);
```

## 高级特性

### 缓存模式

```java
public enum CacheModeEnum {
    L1,      // 仅使用一级缓存（本地缓存）
    L2,      // 仅使用二级缓存（分布式缓存）
    L1_L2    // 组合缓存（推荐）
}
```

### 过期模式

```java
public enum CacheExpireModeEnum {
    WRITE,   // 写入后过期
    ACCESS   // 访问后过期
}
```

### 自定义Key生成器

```java
@Component
public class MyKeyGenerator implements CacheKeyGenerator {
    @Override
    public Object generate(Object target, Method method, Object... params) {
        // 自定义key生成逻辑
        return method.getName() + ":" + Arrays.toString(params);
    }
}
```

### 缓存同步

多实例部署时，L1缓存会自动同步：

- 当一个实例更新/删除缓存时，会通过消息中间件通知其他实例
- 其他实例收到消息后，会同步更新/删除本地L1缓存
- 支持 Redis Pub/Sub 和 Kafka 两种方式

### 防止缓存穿透

通过 `allow-null-values: true` 配置，可以缓存null值：

- 查询结果为null时，也会缓存
- 设置专门的过期时间，避免长期占用内存
- 定期清理过期的null值缓存

## 完整配置参考

详见 [配置指南](CONFIGURATION_GUIDE.md)
