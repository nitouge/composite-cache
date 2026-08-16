# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Added

#### 多级缓存架构
- **L1 本地缓存**：Caffeine（高性能）和 Guava（兼容性）两种实现，统一抽象基类，支持 LoadingCache 和 Manual Cache 两种模式
- **L2 分布式缓存**：基于 Redisson RBucket，支持三种回源策略（NONE / LOCK / LOGICAL_EXPIRE），Pipeline 批量操作优化
- **组合缓存**：L1 + L2 多级联动（CompositeCache），自动降级（L2 故障 → L1），缓存同步（版本门控 + 去重），支持 L1 / L2 / L1_L2 三种模式

#### 防护机制
- **防缓存穿透**：NullValue 空值缓存、独立 NullValue 簿记缓存、可配置过期时间和清理策略、可选布隆过滤器
- **防缓存击穿**：NONE（无保护）、LOCK（本地锁 + 分布式锁）、LOGICAL_EXPIRE（逻辑过期 + 异步刷新）三种策略
- **防缓存雪崩**：TTL 随机抖动、批量操作分片、降级熔断保护
- **一致性增强（可选）**：延迟双删策略、删除失败补偿、主从一致性处理

#### 缓存同步
- Redis Pub/Sub（默认，版本门控 + 去重）和 RabbitMQ 支持，Kafka 配置就绪
- 版本门控（避免旧消息）、消息去重（避免重复处理）、异步发送（不阻塞主流程）、失败补偿机制

#### 运维能力
- **降级熔断**：Redis 不可用时自动降级到 L1，恢复后半开试探自动恢复，可配置故障阈值和并发回源限制
- **监控统计**：Micrometer 集成、Prometheus 指标导出、L1/L2 分层命中率统计、访问链路追踪日志、Actuator 端点集成

#### 开发接口
- **注解支持**：@Cacheable / @CachePut / @CacheEvict / @Caches，支持 SpEL 表达式、条件化缓存、细粒度 L1/L2 配置、自定义 KeyGenerator
- **编程式 API**：统一 CacheManager 接口，支持复杂类型、批量操作（Pipeline 优化）、获取或加载模式、原子操作

#### 配置与架构
- **三层配置体系**：全局配置 / 缓存级配置 / 注解级配置，含启动时参数验证和冲突检测
- **批量操作优化**：Redis Pipeline、可配置批量大小、自动分片处理
- **异步处理**：异步消息发送、异步缓存刷新、非阻塞操作
- **资源管理**：共享守护线程池、有界缓存（自动淘汰）、优雅关闭
- **模块化架构**：composite-cache-core / annotation / spring-boot-starter / test 四个模块
- **Spring Boot 自动配置**：引入依赖即用，无需 @Enable 注解，YAML / Properties 配置
- **Actuator 集成**：/actuator/compositecache 端点，支持全局统计和单个缓存详情查询

### Fixed

- 修复注解参数映射问题
- 修复 CacheManager API 调用问题
- 添加缺失的 spring.factories
- 修复包路径引用错误
- 修复线程池泄漏（改用共享守护线程池）
- 修复空指针风险
- 修复批量操作边界处理

---

## 版本规则

### 语义化版本

遵循 [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html)

- **主版本号（Major）**：不兼容的 API 修改
- **次版本号（Minor）**：向下兼容的功能性新增
- **修订号（Patch）**：向下兼容的问题修正

### 发布周期

- **稳定版本**：每季度发布一次次要版本（Minor）
- **补丁版本**：根据 Bug 修复需要不定期发布（Patch）
- **主要版本**：重大架构调整时发布（Major）

### 版本支持

- **当前版本**：持续维护，Bug 修复和安全更新
- **前一版本**：安全更新和严重 Bug 修复（6 个月）
- **更早版本**：不再维护

---

## 贡献

欢迎贡献代码、报告问题或提出建议！

- 📖 [贡献指南](CONTRIBUTING.md)
- 🐛 [问题反馈](https://github.com/nitouge/composite-cache/issues)
- 💬 [讨论区](https://github.com/nitouge/composite-cache/discussions)

---

## 许可证

本项目采用 Apache License 2.0 许可证。详见 [LICENSE](LICENSE) 文件。
