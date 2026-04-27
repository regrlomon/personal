# Design: SystemPromptBuilder (s10)

**Date:** 2026-04-27  
**Scope:** s10 仅 SystemPromptBuilder  
**Approach:** 方案 A — 依赖注入式 Builder

---

## 背景

`QueryEngine.augmentSystemPrompt()` 目前以 ad-hoc 方式拼接 skill 段、core 和 memory 段，既没有清晰的段边界，也缺少 peragent.md 链和动态环境信息。本设计将该逻辑提取为独立的 `SystemPromptBuilder`，对应 s10 文档中"prompt 是可维护的组装流水线"的核心心智。

---

## 新增文件

### `org.example.agent.engine.SystemPromptBuilder`

**构造器：**

```java
public SystemPromptBuilder(SkillRegistry skillRegistry,   // nullable
                            MemoryStore memoryStore,       // nullable
                            String cwd)
```

**公开方法：**

```java
public String build(String core)
```

按以下顺序组装 6 段，过滤空段后以 `\n\n` 连接：

| 顺序 | 段名 | 来源 | 方法 |
|------|------|------|------|
| 1 | core | 入参 `core` 原样返回 | `buildCore(String)` |
| 2 | tools | 暂留空占位（tools 已走 API tools 数组） | `buildTools()` |
| 3 | skills | `SkillRegistry.describeAvailable()` | `buildSkills()` |
| 4 | memory | `MemoryStore.loadAll()`，格式化为 `## Memories` 段 | `buildMemory()` |
| 5 | peragent_md | peragent.md 链（见下） | `buildPeragentMd()` |
| 6 | dynamic | 日期 + cwd（见下） | `buildDynamic()` |

**各 buildXxx() 方法均使用无访问修饰符（package-private），以便单元测试直接调用。**

---

## peragent.md 链加载

加载路径（按顺序）：

1. `<userHome>/.peragent/peragent.md`（用户全局级）
2. `<cwd>/.peragent/peragent.md`（项目级）

规则：
- 文件不存在或读取失败时静默跳过
- 每个文件内容用来源头标注：

```
=== peragent.md (global) ===
<content>

=== peragent.md (project) ===
<content>
```

- 两个都不存在时 `buildPeragentMd()` 返回空字符串

不实现子目录向上遍历（留作后续扩展）。

---

## Dynamic 段内容

格式：

```
=== Dynamic Context ===
Date: 2026-04-27
CWD:  E:/personal/java/personal
```

- `date`：`LocalDate.now().toString()`，每次 `build()` 调用时取，不缓存
- `cwd`：构造器传入的 `cwd` 字段
- model 名暂不加（需改动 ModelClient 接口，超出本章范围）

---

## QueryEngine 改动

**新增字段：**

```java
private final SystemPromptBuilder promptBuilder;
```

**私有构造器初始化（在现有字段赋值后追加）：**

```java
this.promptBuilder = new SystemPromptBuilder(skillRegistry, memoryStore,
        System.getProperty("user.dir"));
```

**替换 `augmentSystemPrompt`：**

```java
private String augmentSystemPrompt(String base) {
    return promptBuilder.build(base);
}
```

**删除：**
- `buildMemorySection()` 方法（逻辑迁移进 `SystemPromptBuilder.buildMemory()`）

**不改动：**
- 所有构造器签名
- `buildRequest()`、`run()` 方法

---

## 边界

本章不涉及：
- 复杂的 section 注册系统
- 缓存与 token 预算
- 子目录 peragent.md 向上遍历
- model 名注入
- s10a 消息管道（NormalizedMessage、Reminder、Attachment）
