# s05 Skill Loading — Design Spec

**Date:** 2026-04-23  
**Chapter:** s05 (按需知识加载)  
**Approach:** 方案 1 — QueryEngine 重载构造函数 + null 检查

---

## Goal

把"可选领域知识"从常驻 system prompt 里拆出来，改成两层：
- **轻量发现**：system prompt 里只放 skill 目录（name + description）
- **按需加载**：模型真正需要时调用 `load_skill` tool，完整正文作为 tool result 注入当前上下文

---

## Data Structures

### SkillManifest (record)

```java
record SkillManifest(String name, String description)
```

轻量元信息，只用于目录展示。

### SkillDocument (record)

```java
record SkillDocument(SkillManifest manifest, String body)
```

完整内容，仅在 `load_skill` 被调用时返回。

### SkillRegistry

```
SkillRegistry
  Map<String, SkillDocument> skills

  static of(Path skillsDir)      扫描目录，读取所有 SKILL.md，构建注册表
  static empty()                 返回空注册表（无 skill 场景 / 测试）
  describeAvailable(): String    返回目录文字，注入 system prompt
  loadBody(String name): String  按 name 返回完整正文；name 不存在时抛出 IllegalArgumentException
```

---

## Skill 文件布局

```
src/main/resources/skills/
  code-review/
    SKILL.md
  git-workflow/
    SKILL.md
```

### SKILL.md 格式

```markdown
---
name: code-review
description: Checklist for reviewing code changes
---
（完整正文）
```

Frontmatter 以 `---\n` 为分隔符手动解析，无外部依赖。解析规则：
- 第一个 `---` 行开始，到第二个 `---` 行结束为 frontmatter
- 剩余部分为 body（strip leading newline）
- frontmatter 每行格式：`key: value`，只读 `name` 和 `description`

---

## LoadSkillTool

**包：** `org.example.agent.tool.skill`  
**Tool name：** `load_skill`  
**Input schema：** `{ "name": "string" }`

### execute 逻辑

1. 校验 `name` 为非空字符串，否则 `ToolResultEnvelope.error(...)`
2. 调用 `registry.loadBody(name)`
3. 不存在 → `ToolResultEnvelope.error("unknown skill: {name}")`
4. 成功 → 返回：
   ```xml
   <skill name="{name}">{body}</skill>
   ```

> XML tag 包裹防止正文内容与对话上下文混淆，便于模型识别边界。

**注意：** `LoadSkillTool` 由调用方注册进 `ToolRegistry`，QueryEngine 不自动注册。引擎只负责 system prompt 段落注入。

---

## QueryEngine 改动

### 新增构造函数重载

```java
QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, SkillRegistry skillRegistry)
```

内部委托给带 `ExecutorService` 的包内构造函数，同时保存 `skillRegistry` 字段（nullable）。

### buildRequest() 改动

```java
// 如果 skillRegistry 非 null 且有 skill，将目录文字 prepend 到 systemPrompt
String base = params.systemPrompt() != null ? params.systemPrompt() : "";
String skillSection = skillRegistry != null ? skillRegistry.describeAvailable() : "";
String augmentedSystemPrompt = skillSection.isEmpty()
    ? (base.isEmpty() ? null : base)
    : (base.isEmpty() ? skillSection : skillSection + "\n\n" + base);
```

边界说明：
- `skillRegistry` 为 null 或空注册表 → system prompt 不变
- `params.systemPrompt()` 为 null → 视为空串，拼接后不产生 "null" 字样
- 二者都为空 → 保持 null 传给 ModelRequest（现有行为不变）

`describeAvailable()` 输出示例：
```
Skills available:
- code-review: Checklist for reviewing code changes
- git-workflow: Branch and commit guidance
```

### 不变部分

- 现有 `QueryEngine(ModelClient, ToolRegistry)` 和 `QueryEngine(ModelClient, ToolRegistry, ExecutorService)` 签名不变
- 所有现有测试零改动

---

## File Map

| Action | Path |
|--------|------|
| Create | `src/main/java/org/example/agent/tool/skill/SkillManifest.java` |
| Create | `src/main/java/org/example/agent/tool/skill/SkillDocument.java` |
| Create | `src/main/java/org/example/agent/tool/skill/SkillRegistry.java` |
| Create | `src/main/java/org/example/agent/tool/skill/LoadSkillTool.java` |
| Modify | `src/main/java/org/example/agent/engine/QueryEngine.java` |
| Create | `src/main/resources/skills/code-review/SKILL.md` |
| Create | `src/main/resources/skills/git-workflow/SKILL.md` |
| Create | `src/test/java/org/example/agent/tool/skill/SkillRegistryTest.java` |
| Create | `src/test/java/org/example/agent/tool/skill/LoadSkillToolTest.java` |
| Create | `src/test/java/org/example/agent/engine/QueryEngineSkillTest.java` |

---

## Testing Strategy

- `SkillRegistryTest`：用临时目录（`@TempDir`）写入 SKILL.md，验证扫描结果、`describeAvailable()` 格式、`loadBody()` 正常与不存在两种路径
- `LoadSkillToolTest`：用 in-memory `SkillRegistry` 实例，验证 XML 包裹格式、错误路径
- `QueryEngineSkillTest`：lambda stub `ModelClient`，验证 system prompt 被正确 prepend；空 skillRegistry 时 system prompt 不变

---

## 教学边界（不在本章实现）

- 多来源 skill 收集（classpath + 外部目录）
- 条件激活（自动 trigger）
- skill 参数化
- fork 式执行
