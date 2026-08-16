# 贡献指南

首先，感谢您考虑为 Composite Cache 做出贡献！正是像您这样的人，让 Composite Cache 成为一个优秀的工具。

## 目录

- [行为准则](#行为准则)
- [如何贡献](#如何贡献)
  - [报告 Bug](#报告-bug)
  - [建议新功能](#建议新功能)
  - [提交 Pull Request](#提交-pull-request)
- [开发环境搭建](#开发环境搭建)
- [代码规范](#代码规范)
- [测试规范](#测试规范)
- [文档规范](#文档规范)
- [提交信息规范](#提交信息规范)

## 行为准则

本项目及所有参与者受 [Composite Cache 行为准则](CODE_OF_CONDUCT.md) 约束。参与本项目即表示您同意遵守该准则。

## 如何贡献

### 报告 Bug

在创建 Bug 报告之前，请先检查现有 Issue 以避免重复。创建 Bug 报告时，请尽可能包含详细信息：

- **使用清晰的标题**
- **描述重现问题的确切步骤**
- **提供具体示例**（代码示例、配置文件等）
- **描述您观察到的行为和期望的行为**
- **包含环境详情**（Java 版本、Spring Boot 版本等）

创建新 Issue 时请使用 [Bug 报告模板](.github/ISSUE_TEMPLATE/bug_report.md)。

### 建议新功能

欢迎提出功能建议！请：

- **使用清晰的标题**
- **详细描述建议的功能**
- **解释为什么这个功能有用**
- **提供使用示例**

建议新功能时请使用[功能请求模板](.github/ISSUE_TEMPLATE/feature_request.md)。

### 提交 Pull Request

1. **Fork 仓库**并从 `main` 分支创建您的分支
2. **按照代码规范进行修改**
3. **为您的更改添加测试**
4. **确保所有测试通过**（`mvn clean verify`）
5. **更新文档**（如需要）
6. **更新 CHANGELOG.md** 记录您的更改
7. **提交 Pull Request**

## 开发环境搭建

### 前置要求

- JDK 8 或更高版本
- Maven 3.6+
- Redis 6+（用于集成测试）
- Git

### 搭建步骤

```bash
# 克隆您的 Fork
git clone https://github.com/YOUR_USERNAME/composite-cache.git
cd composite-cache

# 添加上游仓库
git remote add upstream https://github.com/nitouge/composite-cache.git

# 安装依赖
mvn clean install

# 运行测试
mvn test

# 运行集成测试（需要 Redis）
mvn verify -P integration-test
```

### 项目结构

```
composite-cache/
├── composite-cache-annotation/      # 注解模块
├── composite-cache-core/           # 核心模块
├── composite-cache-spring-boot-starter/  # Spring Boot Starter
└── composite-cache-test/           # 测试模块
```

## 代码规范

### 代码风格

遵循标准 Java 编码规范：

- **缩进**：4 个空格（不使用 Tab）
- **行宽**：最大 120 字符
- **命名规范**：
  - 类名：`PascalCase`（大驼峰）
  - 方法/变量：`camelCase`（小驼峰）
  - 常量：`UPPER_SNAKE_CASE`（大写下划线）
  - 包名：`lowercase`（小写）

### 代码质量

- **运行 Checkstyle**：`mvn checkstyle:check`
- **运行 SpotBugs**：`mvn spotbugs:check`
- **无编译警告**：提交前修复所有警告

### 最佳实践

1. **保持方法简短**：每个方法尽量少于 50 行
2. **单一职责**：每个类应有一个明确的职责
3. **DRY（不要重复自己）**：将公共代码提取为可复用方法
4. **有意义的命名**：为类、方法和变量使用描述性名称
5. **注释**：为公共 API 添加 JavaDoc，为复杂逻辑添加行内注释

### 代码示例

```java
/**
 * 从缓存或数据库获取用户。
 * 
 * <p>此方法首先检查缓存。如果未找到，则从数据库加载并缓存结果。
 * 
 * @param id 用户 ID
 * @return 用户对象，如果未找到则返回 null
 * @throws IllegalArgumentException 如果 id 为 null
 */
@CacheAble(cacheName = "user", keyExpr = "#id")
public User getUser(Long id) {
    if (id == null) {
        throw new IllegalArgumentException("用户 ID 不能为 null");
    }
    return userRepository.findById(id).orElse(null);
}
```

## 测试规范

### 单元测试

- **覆盖率**：目标 > 80% 代码覆盖率
- **命名**：`methodName_scenario_expectedResult`
- **结构**：遵循 AAA 模式（Arrange, Act, Assert）

```java
@Test
public void get_whenCacheHit_shouldReturnCachedValue() {
    // Arrange（准备）
    cache.put("key1", "value1");
    
    // Act（执行）
    Object result = cache.get("key1");
    
    // Assert（断言）
    assertThat(result).isEqualTo("value1");
}
```

### 集成测试

- 使用 `@SpringBootTest` 进行 Spring 集成测试
- 使用 Testcontainers 进行 Redis 集成测试
- 在 `@AfterEach` 中清理资源

```java
@SpringBootTest
@Testcontainers
public class CacheIntegrationTest {
    
    @Container
    private static final GenericContainer<?> redis = 
        new GenericContainer<>("redis:6-alpine")
            .withExposedPorts(6379);
    
    @Test
    public void testCacheIntegration() {
        // 您的测试代码
    }
}
```

### 运行测试

```bash
# 运行单元测试
mvn test

# 运行集成测试
mvn verify -P integration-test

# 运行特定测试
mvn test -Dtest=CacheTest

# 生成覆盖率报告
mvn jacoco:report
```

## 文档规范

### JavaDoc

- **所有公共 API** 必须有 JavaDoc
- 包含 `@param`、`@return`、`@throws` 标签
- 为复杂 API 添加使用示例
- 使用 `<p>`、`<ul>`、`<pre>` 进行格式化

### README 更新

- 添加新功能时更新 README.md
- 包含代码示例
- 更新版本号
- 保持简洁清晰

### CHANGELOG 更新

遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/) 格式：

```markdown
## [1.1.0] - 2026-01-18

### ✨ Added
- 使用 Micrometer 添加监控支持
- 添加健康检查端点

### 📝 Changed
- 改进错误消息

### 🐛 Fixed
- 修复缓存同步问题
```

## 提交信息规范

遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/) 规范：

### 格式

```
<type>(<scope>): <subject>

<body>

<footer>
```

### 类型

- `feat`: 新功能
- `fix`: Bug 修复
- `docs`: 文档更改
- `style`: 代码格式更改（不影响功能）
- `refactor`: 代码重构
- `perf`: 性能改进
- `test`: 添加或更新测试
- `chore`: 构建或辅助工具的变动

### 示例

```
feat(core): 添加 Micrometer 监控支持

- 集成 Micrometer 进行指标收集
- 添加缓存命中率追踪
- 添加延迟监控

Closes #123
```

```
fix(annotation): 修复内部调用绕过 AOP 代理的问题

问题由直接方法调用绕过代理引起。
添加了说明此限制的文档。

Fixes #456
```

## 代码审查流程

1. **自动检查**：CI 必须通过（构建、测试、代码质量）
2. **代码审查**：至少一位维护者必须批准
3. **测试**：验证更改按预期工作
4. **文档**：确保文档已更新
5. **合并**：维护者将在批准后合并

## 获取帮助

- **问题咨询**：在 [GitHub Discussions](https://github.com/nitouge/composite-cache/discussions) 发起讨论
- **Bug 反馈**：在 [GitHub Issues](https://github.com/nitouge/composite-cache/issues) 创建 Issue
- **即时沟通**：加入我们的交流群（如有）

## 贡献者致谢

贡献者将会：
- 列入 CHANGELOG.md
- 在发布说明中提及
- 添加到 CONTRIBUTORS.md（如有重要贡献）

## 许可证

通过贡献，您同意您的贡献将在 Apache License 2.0 下授权。

---

感谢您为 Composite Cache 做出贡献！🎉
