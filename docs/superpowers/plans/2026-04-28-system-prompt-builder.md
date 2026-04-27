# SystemPromptBuilder Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `QueryEngine` 中散乱的 system prompt 拼接逻辑提取为独立的 `SystemPromptBuilder`，增加 peragent.md 链加载和动态环境信息段。

**Architecture:** 新建 `SystemPromptBuilder`（依赖注入 SkillRegistry、MemoryStore、cwd），对外暴露 `build(String core)` 方法，内部 6 个 package-private 方法各负责一段；`QueryEngine` 将 `augmentSystemPrompt` 完全委托给它，并删除原 `buildMemorySection`。

**Tech Stack:** Java 21, JUnit Jupiter 5, Maven (`mvn -Dtest=ClassName test -q`)

---

## File Map

| 操作 | 路径 |
|------|------|
| Create | `src/main/java/org/example/agent/engine/SystemPromptBuilder.java` |
| Create | `src/test/java/org/example/agent/engine/SystemPromptBuilderTest.java` |
| Modify | `src/main/java/org/example/agent/engine/QueryEngine.java` |
| Modify | `src/test/java/org/example/agent/engine/QueryEngineSkillTest.java` |
| Modify | `src/test/java/org/example/agent/engine/QueryEngineMemoryTest.java` |

---

## Task 1: 骨架 + buildCore + buildTools + buildDynamic

**Files:**
- Create: `src/main/java/org/example/agent/engine/SystemPromptBuilder.java`
- Create: `src/test/java/org/example/agent/engine/SystemPromptBuilderTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/org/example/agent/engine/SystemPromptBuilderTest.java
package org.example.agent.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptBuilderTest {

    @TempDir
    Path tempDir;

    private SystemPromptBuilder builder() {
        return new SystemPromptBuilder(null, null, tempDir.toString());
    }

    @Test
    void buildCore_returns_core_unchanged() {
        assertEquals("hello", builder().buildCore("hello"));
    }

    @Test
    void buildCore_returns_empty_for_null() {
        assertEquals("", builder().buildCore(null));
    }

    @Test
    void buildTools_returns_empty() {
        assertEquals("", builder().buildTools());
    }

    @Test
    void buildDynamic_contains_today_and_cwd() {
        var dynamic = builder().buildDynamic();
        assertTrue(dynamic.startsWith("=== Dynamic Context ==="),
                "must start with header");
        assertTrue(dynamic.contains("Date: " + LocalDate.now()),
                "must contain today's date");
        assertTrue(dynamic.contains("CWD:  " + tempDir),
                "must contain cwd");
    }
}
```

- [ ] **Step 2: 确认测试失败**

```
mvn -Dtest=SystemPromptBuilderTest test -q
```
期望：编译报错（SystemPromptBuilder 不存在）

- [ ] **Step 3: 创建 `SystemPromptBuilder` 骨架实现**

```java
// src/main/java/org/example/agent/engine/SystemPromptBuilder.java
package org.example.agent.engine;

import org.example.agent.tool.skill.SkillRegistry;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

public class SystemPromptBuilder {

    private final SkillRegistry skillRegistry;
    private final MemoryStore memoryStore;
    private final String cwd;

    public SystemPromptBuilder(SkillRegistry skillRegistry, MemoryStore memoryStore, String cwd) {
        this.skillRegistry = skillRegistry;
        this.memoryStore = memoryStore;
        this.cwd = cwd;
    }

    public String build(String core) {
        return List.of(
                buildCore(core),
                buildTools(),
                buildSkills(),
                buildMemory(),
                buildPeragentMd(),
                buildDynamic()
        ).stream()
         .filter(s -> !s.isEmpty())
         .collect(Collectors.joining("\n\n"));
    }

    String buildCore(String core) {
        return core != null ? core : "";
    }

    String buildTools() {
        return "";
    }

    String buildSkills() {
        return "";
    }

    String buildMemory() {
        return "";
    }

    String buildPeragentMd() {
        return "";
    }

    String buildDynamic() {
        return "=== Dynamic Context ===\n" +
               "Date: " + LocalDate.now() + "\n" +
               "CWD:  " + cwd;
    }
}
```

- [ ] **Step 4: 确认测试通过**

```
mvn -Dtest=SystemPromptBuilderTest test -q
```
期望：无输出（全部通过）

- [ ] **Step 5: 提交**

```
git add src/main/java/org/example/agent/engine/SystemPromptBuilder.java \
        src/test/java/org/example/agent/engine/SystemPromptBuilderTest.java
git commit -m "feat(s10): add SystemPromptBuilder skeleton with buildCore/buildTools/buildDynamic"
```

---

## Task 2: buildSkills + buildMemory

**Files:**
- Modify: `src/main/java/org/example/agent/engine/SystemPromptBuilder.java`
- Modify: `src/test/java/org/example/agent/engine/SystemPromptBuilderTest.java`

- [ ] **Step 1: 追加失败测试**

在 `SystemPromptBuilderTest` 末尾追加：

```java
    // ---- buildSkills ----

    @Test
    void buildSkills_returns_empty_when_registry_is_null() {
        assertEquals("", builder().buildSkills());
    }

    @Test
    void buildSkills_returns_description_from_registry() {
        var doc = new org.example.agent.tool.skill.SkillDocument(
                new org.example.agent.tool.skill.SkillManifest("my-skill", "Does stuff"), "body");
        var registry = new org.example.agent.tool.skill.SkillRegistry(java.util.Map.of("my-skill", doc));
        var b = new SystemPromptBuilder(registry, null, tempDir.toString());
        assertTrue(b.buildSkills().contains("my-skill: Does stuff"));
    }

    // ---- buildMemory ----

    @Test
    void buildMemory_returns_empty_when_store_is_null() {
        assertEquals("", builder().buildMemory());
    }

    @Test
    void buildMemory_returns_empty_when_store_has_no_entries() throws Exception {
        var store = new MemoryStore(tempDir.resolve(".memory"));
        var b = new SystemPromptBuilder(null, store, tempDir.toString());
        assertEquals("", b.buildMemory());
    }

    @Test
    void buildMemory_formats_entries_under_memories_header() throws Exception {
        var store = new MemoryStore(tempDir.resolve(".memory"));
        store.save(new org.example.agent.core.MemoryEntry(
                "key1", "desc", "user", "Remember this."));
        var b = new SystemPromptBuilder(null, store, tempDir.toString());
        var result = b.buildMemory();
        assertTrue(result.startsWith("## Memories"));
        assertTrue(result.contains("key1 [user]"));
        assertTrue(result.contains("Remember this."));
    }
```

- [ ] **Step 2: 确认测试失败**

```
mvn -Dtest=SystemPromptBuilderTest test -q
```
期望：部分测试失败（buildSkills/buildMemory 返回空字符串）

- [ ] **Step 3: 实现 buildSkills 和 buildMemory**

将 `SystemPromptBuilder` 中的两个方法替换：

```java
    String buildSkills() {
        if (skillRegistry == null) return "";
        return skillRegistry.describeAvailable();
    }

    String buildMemory() {
        if (memoryStore == null) return "";
        try {
            var entries = memoryStore.loadAll();
            if (entries.isEmpty()) return "";
            var sb = new StringBuilder("## Memories\n");
            for (var e : entries) {
                sb.append("\n### ").append(e.name()).append(" [").append(e.type()).append("]\n");
                sb.append(e.content()).append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (java.io.IOException e) {
            return "";
        }
    }
```

- [ ] **Step 4: 确认测试通过**

```
mvn -Dtest=SystemPromptBuilderTest test -q
```
期望：无输出

- [ ] **Step 5: 提交**

```
git add src/main/java/org/example/agent/engine/SystemPromptBuilder.java \
        src/test/java/org/example/agent/engine/SystemPromptBuilderTest.java
git commit -m "feat(s10): implement buildSkills and buildMemory in SystemPromptBuilder"
```

---

## Task 3: buildPeragentMd

**Files:**
- Modify: `src/main/java/org/example/agent/engine/SystemPromptBuilder.java`
- Modify: `src/test/java/org/example/agent/engine/SystemPromptBuilderTest.java`

- [ ] **Step 1: 追加失败测试**

在 `SystemPromptBuilderTest` 末尾追加：

```java
    // ---- buildPeragentMd ----

    @Test
    void buildPeragentMd_returns_empty_when_no_files_exist() {
        assertEquals("", builder().buildPeragentMd());
    }

    @Test
    void buildPeragentMd_loads_project_file_when_present() throws Exception {
        var peragentDir = tempDir.resolve(".peragent");
        java.nio.file.Files.createDirectories(peragentDir);
        java.nio.file.Files.writeString(peragentDir.resolve("peragent.md"), "project rules");
        var result = builder().buildPeragentMd();
        assertTrue(result.contains("=== peragent.md (project) ==="),
                "must include project label");
        assertTrue(result.contains("project rules"),
                "must include file content");
    }

    @Test
    void buildPeragentMd_both_files_appear_in_order() throws Exception {
        // simulate global file via a second temp dir acting as userHome
        var fakeHome = tempDir.resolve("home");
        var globalDir = fakeHome.resolve(".peragent");
        java.nio.file.Files.createDirectories(globalDir);
        java.nio.file.Files.writeString(globalDir.resolve("peragent.md"), "global rules");

        var projectDir = tempDir.resolve(".peragent");
        java.nio.file.Files.createDirectories(projectDir);
        java.nio.file.Files.writeString(projectDir.resolve("peragent.md"), "project rules");

        // Use a subclass to inject the fake home
        var b = new SystemPromptBuilder(null, null, tempDir.toString()) {
            @Override
            String buildPeragentMd() {
                var sb = new StringBuilder();
                appendPeragentFile(sb, globalDir.resolve("peragent.md"), "global");
                appendPeragentFile(sb, tempDir.resolve(".peragent/peragent.md"), "project");
                return sb.toString().stripTrailing();
            }
        };
        var result = b.buildPeragentMd();
        assertTrue(result.contains("=== peragent.md (global) ==="));
        assertTrue(result.contains("global rules"));
        assertTrue(result.contains("=== peragent.md (project) ==="));
        assertTrue(result.contains("project rules"));
        assertTrue(result.indexOf("global") < result.indexOf("project"),
                "global must appear before project");
    }
```

> **注意：** `buildPeragentMd_both_files_appear_in_order` 测试用匿名子类绕开 `System.getProperty("user.home")` 的不确定性，直接调用 `appendPeragentFile`（Task 3 Step 3 中以 package-private 可见性暴露）。

- [ ] **Step 2: 确认测试失败**

```
mvn -Dtest=SystemPromptBuilderTest test -q
```
期望：buildPeragentMd_loads_project_file_when_present 和 both_files 失败

- [ ] **Step 3: 实现 buildPeragentMd**

将 `SystemPromptBuilder` 中的 `buildPeragentMd` 方法替换，并新增 `appendPeragentFile`：

```java
    String buildPeragentMd() {
        var sb = new StringBuilder();
        var userHome = System.getProperty("user.home");
        appendPeragentFile(sb, java.nio.file.Path.of(userHome, ".peragent", "peragent.md"), "global");
        appendPeragentFile(sb, java.nio.file.Path.of(cwd, ".peragent", "peragent.md"), "project");
        return sb.toString().stripTrailing();
    }

    void appendPeragentFile(StringBuilder sb, java.nio.file.Path path, String label) {
        if (!java.nio.file.Files.exists(path)) return;
        try {
            var content = java.nio.file.Files.readString(path).stripTrailing();
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append("=== peragent.md (").append(label).append(") ===\n");
            sb.append(content);
        } catch (java.io.IOException e) {
            // silently skip
        }
    }
```

更新测试中的辅助调用名称：无需修改，Step 1 中已直接使用 `appendPeragentFile`。

- [ ] **Step 4: 确认测试通过**

```
mvn -Dtest=SystemPromptBuilderTest test -q
```
期望：无输出

- [ ] **Step 5: 提交**

```
git add src/main/java/org/example/agent/engine/SystemPromptBuilder.java \
        src/test/java/org/example/agent/engine/SystemPromptBuilderTest.java
git commit -m "feat(s10): implement buildPeragentMd with global+project chain loading"
```

---

## Task 4: build() 集成验证

**Files:**
- Modify: `src/test/java/org/example/agent/engine/SystemPromptBuilderTest.java`

- [ ] **Step 1: 追加集成测试**

在 `SystemPromptBuilderTest` 末尾追加：

```java
    // ---- build() integration ----

    @Test
    void build_filters_empty_sections_and_joins_with_double_newline() {
        // null registry, null store, no peragent files → only core + dynamic
        var b = new SystemPromptBuilder(null, null, tempDir.toString());
        var result = b.build("my core");
        assertTrue(result.contains("my core"), "core must be present");
        assertTrue(result.contains("=== Dynamic Context ==="), "dynamic must be present");
        assertFalse(result.contains("\n\n\n"), "must not have triple newline (empty section leaked)");
    }

    @Test
    void build_null_core_does_not_produce_literal_null() {
        var result = builder().build(null);
        assertFalse(result.contains("null"), "must not contain literal 'null'");
        assertTrue(result.contains("=== Dynamic Context ==="));
    }

    @Test
    void build_includes_all_non_empty_sections_in_order() throws Exception {
        // skills
        var doc = new org.example.agent.tool.skill.SkillDocument(
                new org.example.agent.tool.skill.SkillManifest("sk", "Skill desc"), "body");
        var registry = new org.example.agent.tool.skill.SkillRegistry(java.util.Map.of("sk", doc));

        // memory
        var store = new MemoryStore(tempDir.resolve(".memory"));
        store.save(new org.example.agent.core.MemoryEntry("k", "d", "user", "mem content"));

        // peragent project file
        var peragentDir = tempDir.resolve(".peragent");
        java.nio.file.Files.createDirectories(peragentDir);
        java.nio.file.Files.writeString(peragentDir.resolve("peragent.md"), "project rule");

        var b = new SystemPromptBuilder(registry, store, tempDir.toString());
        var result = b.build("core text");

        // all sections present
        assertTrue(result.contains("core text"));
        assertTrue(result.contains("sk: Skill desc"));
        assertTrue(result.contains("## Memories"));
        assertTrue(result.contains("mem content"));
        assertTrue(result.contains("=== peragent.md (project) ==="));
        assertTrue(result.contains("project rule"));
        assertTrue(result.contains("=== Dynamic Context ==="));

        // order: core → skills → memory → peragent → dynamic
        int iCore    = result.indexOf("core text");
        int iSkills  = result.indexOf("sk: Skill desc");
        int iMemory  = result.indexOf("## Memories");
        int iPeragent= result.indexOf("=== peragent.md");
        int iDynamic = result.indexOf("=== Dynamic Context ===");
        assertTrue(iCore < iSkills, "core before skills");
        assertTrue(iSkills < iMemory, "skills before memory");
        assertTrue(iMemory < iPeragent, "memory before peragent");
        assertTrue(iPeragent < iDynamic, "peragent before dynamic");
    }
```

- [ ] **Step 2: 确认测试通过**

```
mvn -Dtest=SystemPromptBuilderTest test -q
```
期望：无输出（build() 方法在 Task 1 中已实现）

- [ ] **Step 3: 提交**

```
git add src/test/java/org/example/agent/engine/SystemPromptBuilderTest.java
git commit -m "test(s10): add build() integration tests for SystemPromptBuilder"
```

---

## Task 5: 接入 QueryEngine + 修复已有测试

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Modify: `src/test/java/org/example/agent/engine/QueryEngineSkillTest.java`
- Modify: `src/test/java/org/example/agent/engine/QueryEngineMemoryTest.java`

- [ ] **Step 1: 修改 QueryEngine**

**1a. 新增字段**（在现有字段声明块末尾，`HookRunner hookRunner;` 下方）：

```java
    private final SystemPromptBuilder promptBuilder;
```

**1b. 私有构造器末尾**（`toolRegistry.register(new CompactTool(...))` 之后）追加：

```java
        this.promptBuilder = new SystemPromptBuilder(skillRegistry, memoryStore,
                System.getProperty("user.dir"));
```

**1c. 替换 `augmentSystemPrompt` 方法体：**

```java
    private String augmentSystemPrompt(String base) {
        return promptBuilder.build(base);
    }
```

**1d. 删除 `buildMemorySection()` 方法**（整个方法，约第 228–242 行）。

- [ ] **Step 2: 确认已有测试当前失败状态（预期失败）**

```
mvn -Dtest=QueryEngineSkillTest,QueryEngineMemoryTest test -q 2>&1 | grep -E "FAIL|ERROR|Tests run"
```
期望：几个测试失败（因为 dynamic 段现在总会追加，且 skills 顺序变了）

- [ ] **Step 3: 修复 QueryEngineSkillTest**

将以下 4 处断言更新：

**`skill_directory_is_prepended_to_system_prompt`** — 把 `startsWith` 改为 `contains`：

```java
        assertTrue(sp.contains("Skills available:"), "should contain skill section");
        assertTrue(sp.contains("foo: Foo skill"), "should list skill");
        assertTrue(sp.contains("base prompt"), "should retain base prompt");
```

**`null_system_prompt_produces_skill_section_only`** — 已有断言可保持（contains 不受顺序影响），增加确认不含 "null"：

```java
        var sp = captured.get(0).systemPrompt();
        assertNotNull(sp);
        assertTrue(sp.contains("bar: Bar skill"));
        assertFalse(sp.contains("null"), "must not contain literal 'null'");
```

**`empty_registry_leaves_system_prompt_unchanged`** — 改为 contains：

```java
        var sp = captured.get(0).systemPrompt();
        assertTrue(sp.contains("original prompt"), "base prompt must be present");
        assertTrue(sp.contains("=== Dynamic Context ==="), "dynamic section must be present");
```

**`no_skill_registry_leaves_system_prompt_unchanged`** — 同上：

```java
        var sp = captured.get(0).systemPrompt();
        assertTrue(sp.contains("original prompt"), "base prompt must be present");
        assertTrue(sp.contains("=== Dynamic Context ==="), "dynamic section must be present");
```

- [ ] **Step 4: 修复 QueryEngineMemoryTest**

**`no_memory_section_when_store_is_null`** — 改为 contains：

```java
        assertTrue(capturedPrompt[0].contains("just base"), "base prompt must be present");
        assertFalse(capturedPrompt[0].contains("## Memories"), "must not have memory section");
```

**`no_memory_section_when_memory_dir_is_empty`** — 同上：

```java
        assertTrue(capturedPrompt[0].contains("base only"), "base prompt must be present");
        assertFalse(capturedPrompt[0].contains("## Memories"), "must not have memory section");
```

- [ ] **Step 5: 确认所有测试通过**

```
mvn test -q
```
期望：无输出（全部通过）

- [ ] **Step 6: 提交**

```
git add src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/engine/QueryEngineSkillTest.java \
        src/test/java/org/example/agent/engine/QueryEngineMemoryTest.java
git commit -m "feat(s10): wire SystemPromptBuilder into QueryEngine, remove buildMemorySection"
```
