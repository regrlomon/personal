# Memory System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement s09 Memory System — `save_memory` / `delete_memory` tools backed by `.memory/` directory, with automatic injection into the QueryEngine system prompt at session start.

**Architecture:** A `MemoryStore` service class handles all file I/O in `{workDir}/.memory/`; two `Tool` implementations (`SaveMemoryTool`, `DeleteMemoryTool`) delegate to it; `QueryEngine` accepts `MemoryStore` as an optional injected dependency and prepends a `## Memories` section to the system prompt when memories exist.

**Tech Stack:** Java 21, JUnit Jupiter 5, standard library only (no YAML parser — frontmatter is parsed with plain string operations).

**Spec:** `docs/superpowers/specs/2026-04-23-memory-system-design.md`

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/org/example/agent/core/MemoryEntry.java` | Immutable data record |
| Create | `src/main/java/org/example/agent/engine/MemoryStore.java` | File I/O: save, delete, loadAll, rebuildIndex |
| Create | `src/main/java/org/example/agent/tool/SaveMemoryTool.java` | Tool: delegates save to MemoryStore |
| Create | `src/main/java/org/example/agent/tool/DeleteMemoryTool.java` | Tool: delegates delete to MemoryStore |
| Modify | `src/main/java/org/example/agent/engine/QueryEngine.java` | Inject MemoryStore, augment system prompt |
| Create | `src/test/java/org/example/agent/engine/MemoryStoreTest.java` | Unit tests for MemoryStore |
| Create | `src/test/java/org/example/agent/tool/SaveMemoryToolTest.java` | Unit tests for SaveMemoryTool |
| Create | `src/test/java/org/example/agent/tool/DeleteMemoryToolTest.java` | Unit tests for DeleteMemoryTool |
| Create | `src/test/java/org/example/agent/engine/QueryEngineMemoryTest.java` | Integration test: memory in system prompt |

---

## Task 1: `MemoryEntry` record

**Files:**
- Create: `src/main/java/org/example/agent/core/MemoryEntry.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/example/agent/engine/MemoryStoreTest.java` (we'll add more tests here in Task 2 — for now a placeholder that verifies `MemoryEntry` compiles):

```java
package org.example.agent.engine;

import org.example.agent.core.MemoryEntry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    @Test
    void memoryEntry_stores_fields() {
        var entry = new MemoryEntry("pref_tabs", "Use tabs", "user", "Always use tabs.");
        assertEquals("pref_tabs", entry.name());
        assertEquals("Use tabs", entry.description());
        assertEquals("user", entry.type());
        assertEquals("Always use tabs.", entry.content());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn -Dtest=MemoryStoreTest test
```

Expected: FAIL — `MemoryEntry` does not exist yet.

- [ ] **Step 3: Create `MemoryEntry`**

```java
package org.example.agent.core;

public record MemoryEntry(
        String name,
        String description,
        String type,
        String content
) {}
```

- [ ] **Step 4: Run test to verify it passes**

```
mvn -Dtest=MemoryStoreTest test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/core/MemoryEntry.java \
        src/test/java/org/example/agent/engine/MemoryStoreTest.java
git commit -m "feat(memory): add MemoryEntry record"
```

---

## Task 2: `MemoryStore` — save, loadAll, rebuildIndex

**Files:**
- Create: `src/main/java/org/example/agent/engine/MemoryStore.java`
- Modify: `src/test/java/org/example/agent/engine/MemoryStoreTest.java`

- [ ] **Step 1: Add failing tests for save and loadAll**

Replace the contents of `src/test/java/org/example/agent/engine/MemoryStoreTest.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.MemoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    @TempDir
    Path memoryDir;

    MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(memoryDir);
    }

    @Test
    void memoryEntry_stores_fields() {
        var entry = new MemoryEntry("pref_tabs", "Use tabs", "user", "Always use tabs.");
        assertEquals("pref_tabs", entry.name());
        assertEquals("Use tabs", entry.description());
        assertEquals("user", entry.type());
        assertEquals("Always use tabs.", entry.content());
    }

    @Test
    void loadAll_returns_empty_when_directory_absent() throws IOException {
        var store2 = new MemoryStore(memoryDir.resolve("nonexistent"));
        assertEquals(0, store2.loadAll().size());
    }

    @Test
    void save_creates_file_and_can_be_loaded() throws IOException {
        var entry = new MemoryEntry("pref_tabs", "Use tabs", "user", "Always use tabs.");
        store.save(entry);

        var loaded = store.loadAll();
        assertEquals(1, loaded.size());
        var e = loaded.get(0);
        assertEquals("pref_tabs", e.name());
        assertEquals("Use tabs", e.description());
        assertEquals("user", e.type());
        assertEquals("Always use tabs.", e.content());
    }

    @Test
    void save_overwrites_existing_entry_with_same_name() throws IOException {
        store.save(new MemoryEntry("note", "A note", "project", "Original content."));
        store.save(new MemoryEntry("note", "A note updated", "project", "Updated content."));

        var loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("Updated content.", loaded.get(0).content());
        assertEquals("A note updated", loaded.get(0).description());
    }

    @Test
    void save_creates_memory_dir_if_absent() throws IOException {
        var nested = memoryDir.resolve("sub").resolve(".memory");
        var s = new MemoryStore(nested);
        s.save(new MemoryEntry("x", "desc", "user", "body"));
        assertTrue(Files.exists(nested));
    }

    @Test
    void save_rebuilds_index_file() throws IOException {
        store.save(new MemoryEntry("key1", "First entry", "feedback", "content1"));
        store.save(new MemoryEntry("key2", "Second entry", "project", "content2"));

        var index = Files.readString(memoryDir.resolve("MEMORY.md"));
        assertTrue(index.contains("key1"));
        assertTrue(index.contains("First entry"));
        assertTrue(index.contains("key2"));
        assertTrue(index.contains("Second entry"));
    }

    @Test
    void loadAll_skips_files_with_malformed_frontmatter() throws IOException {
        store.save(new MemoryEntry("good", "Good", "user", "body"));
        Files.writeString(memoryDir.resolve("bad.md"), "no frontmatter here");

        var loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("good", loaded.get(0).name());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn -Dtest=MemoryStoreTest test
```

Expected: FAIL — `MemoryStore` does not exist yet.

- [ ] **Step 3: Create `MemoryStore`**

```java
package org.example.agent.engine;

import org.example.agent.core.MemoryEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MemoryStore {

    private final Path memoryDir;

    public MemoryStore(Path memoryDir) {
        this.memoryDir = memoryDir;
    }

    public void save(MemoryEntry entry) throws IOException {
        Files.createDirectories(memoryDir);
        var frontmatter = "---\n" +
                "name: " + entry.name() + "\n" +
                "description: " + entry.description() + "\n" +
                "type: " + entry.type() + "\n" +
                "---\n";
        Files.writeString(memoryDir.resolve(entry.name() + ".md"), frontmatter + entry.content());
        rebuildIndex();
    }

    public boolean delete(String name) throws IOException {
        var file = memoryDir.resolve(name + ".md");
        if (!Files.exists(file)) return false;
        Files.delete(file);
        rebuildIndex();
        return true;
    }

    public List<MemoryEntry> loadAll() throws IOException {
        if (!Files.exists(memoryDir)) return List.of();
        List<MemoryEntry> entries = new ArrayList<>();
        try (var stream = Files.list(memoryDir)) {
            var files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("MEMORY.md"))
                    .sorted()
                    .toList();
            for (var file : files) {
                var entry = parseFile(file);
                if (entry != null) entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private void rebuildIndex() throws IOException {
        var entries = loadAll();
        var sb = new StringBuilder("# Memory Index\n\n");
        for (var e : entries) {
            sb.append("- [").append(e.name()).append("](").append(e.name()).append(".md) — ")
              .append(e.description()).append(" [").append(e.type()).append("]\n");
        }
        Files.writeString(memoryDir.resolve("MEMORY.md"), sb.toString());
    }

    private static MemoryEntry parseFile(Path file) throws IOException {
        var text = Files.readString(file);
        if (!text.startsWith("---\n")) return null;
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) return null;
        var frontmatter = text.substring(4, end);
        var content = text.substring(end + 5);
        var fields = new HashMap<String, String>();
        for (var line : frontmatter.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                fields.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        var name = fields.get("name");
        var description = fields.get("description");
        var type = fields.get("type");
        if (name == null || description == null || type == null) return null;
        return new MemoryEntry(name, description, type, content);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
mvn -Dtest=MemoryStoreTest test
```

Expected: PASS (all 8 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/engine/MemoryStore.java \
        src/test/java/org/example/agent/engine/MemoryStoreTest.java
git commit -m "feat(memory): add MemoryStore with save/loadAll/rebuildIndex"
```

---

## Task 3: `MemoryStore` — delete

**Files:**
- Modify: `src/test/java/org/example/agent/engine/MemoryStoreTest.java`

(`MemoryStore.delete()` is already written in Task 2 — this task adds tests to verify it.)

- [ ] **Step 1: Add delete tests to `MemoryStoreTest`**

Append these test methods to the class (inside the `}` of `MemoryStoreTest`):

```java
    @Test
    void delete_removes_file_and_returns_true() throws IOException {
        store.save(new MemoryEntry("to_delete", "Will be removed", "user", "content"));
        assertTrue(store.delete("to_delete"));
        assertEquals(0, store.loadAll().size());
    }

    @Test
    void delete_returns_false_when_not_found() throws IOException {
        assertFalse(store.delete("nonexistent"));
    }

    @Test
    void delete_rebuilds_index_after_removal() throws IOException {
        store.save(new MemoryEntry("keep", "Keep this", "project", "keep content"));
        store.save(new MemoryEntry("remove", "Remove this", "project", "remove content"));
        store.delete("remove");

        var index = Files.readString(memoryDir.resolve("MEMORY.md"));
        assertTrue(index.contains("keep"));
        assertFalse(index.contains("remove"));
    }
```

- [ ] **Step 2: Run tests to verify they pass**

```
mvn -Dtest=MemoryStoreTest test
```

Expected: PASS (all 11 tests, including the 3 new delete tests).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/example/agent/engine/MemoryStoreTest.java
git commit -m "test(memory): add delete tests for MemoryStore"
```

---

## Task 4: `SaveMemoryTool`

**Files:**
- Create: `src/main/java/org/example/agent/tool/SaveMemoryTool.java`
- Create: `src/test/java/org/example/agent/tool/SaveMemoryToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.example.agent.tool;

import org.example.agent.engine.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SaveMemoryToolTest {

    @TempDir
    Path dir;

    MemoryStore store;
    SaveMemoryTool tool;
    ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(dir);
        tool = new SaveMemoryTool(store);
        ctx = ToolUseContext.defaults(dir.toString());
    }

    @Test
    void execute_saves_memory_and_returns_success_message() throws IOException {
        var result = tool.execute(Map.of(
                "name", "prefer_tabs",
                "description", "Use tabs not spaces",
                "type", "user",
                "content", "Always indent with tabs."
        ), ctx);

        assertFalse(result.isError());
        assertEquals("Memory saved: prefer_tabs", result.content());
        assertEquals(1, store.loadAll().size());
        assertEquals("prefer_tabs", store.loadAll().get(0).name());
    }

    @Test
    void execute_overwrites_existing_memory_with_same_name() throws IOException {
        tool.execute(Map.of("name", "n", "description", "d", "type", "user", "content", "v1"), ctx);
        tool.execute(Map.of("name", "n", "description", "d2", "type", "user", "content", "v2"), ctx);

        var entries = store.loadAll();
        assertEquals(1, entries.size());
        assertEquals("v2", entries.get(0).content());
    }

    @Test
    void tool_name_is_save_memory() {
        assertEquals("save_memory", tool.definition().name());
    }

    @Test
    void definition_requires_all_four_fields() {
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) tool.definition().inputSchema().get("required");
        assertTrue(required.contains("name"));
        assertTrue(required.contains("description"));
        assertTrue(required.contains("type"));
        assertTrue(required.contains("content"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn -Dtest=SaveMemoryToolTest test
```

Expected: FAIL — `SaveMemoryTool` does not exist yet.

- [ ] **Step 3: Create `SaveMemoryTool`**

```java
package org.example.agent.tool;

import org.example.agent.core.MemoryEntry;
import org.example.agent.core.ToolDefinition;
import org.example.agent.engine.MemoryStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class SaveMemoryTool implements Tool {

    private final MemoryStore store;

    public SaveMemoryTool(MemoryStore store) {
        this.store = store;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "save_memory",
                "Save or update a memory entry. An existing entry with the same name is overwritten.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "Unique identifier for this memory"),
                                "description", Map.of("type", "string", "description", "One-line summary for the memory index"),
                                "type", Map.of("type", "string", "description", "Category: user | feedback | project | reference"),
                                "content", Map.of("type", "string", "description", "Memory body text")
                        ),
                        "required", List.of("name", "description", "type", "content")
                )
        );
    }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var entry = new MemoryEntry(
                (String) input.get("name"),
                (String) input.get("description"),
                (String) input.get("type"),
                (String) input.get("content")
        );
        try {
            store.save(entry);
            return ToolResultEnvelope.success("Memory saved: " + entry.name());
        } catch (IOException e) {
            return ToolResultEnvelope.error("Error saving memory: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```
mvn -Dtest=SaveMemoryToolTest test
```

Expected: PASS (all 4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/SaveMemoryTool.java \
        src/test/java/org/example/agent/tool/SaveMemoryToolTest.java
git commit -m "feat(memory): add SaveMemoryTool"
```

---

## Task 5: `DeleteMemoryTool`

**Files:**
- Create: `src/main/java/org/example/agent/tool/DeleteMemoryTool.java`
- Create: `src/test/java/org/example/agent/tool/DeleteMemoryToolTest.java`

- [ ] **Step 1: Write the failing test**

```java
package org.example.agent.tool;

import org.example.agent.core.MemoryEntry;
import org.example.agent.engine.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeleteMemoryToolTest {

    @TempDir
    Path dir;

    MemoryStore store;
    DeleteMemoryTool tool;
    ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(dir);
        tool = new DeleteMemoryTool(store);
        ctx = ToolUseContext.defaults(dir.toString());
    }

    @Test
    void execute_deletes_existing_memory_and_returns_success() throws IOException {
        store.save(new MemoryEntry("to_go", "desc", "user", "body"));

        var result = tool.execute(Map.of("name", "to_go"), ctx);

        assertFalse(result.isError());
        assertEquals("Memory deleted: to_go", result.content());
        assertEquals(0, store.loadAll().size());
    }

    @Test
    void execute_returns_not_found_message_when_absent() {
        var result = tool.execute(Map.of("name", "ghost"), ctx);

        assertFalse(result.isError());
        assertEquals("Memory not found: ghost", result.content());
    }

    @Test
    void tool_name_is_delete_memory() {
        assertEquals("delete_memory", tool.definition().name());
    }

    @Test
    void definition_requires_name_field() {
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) tool.definition().inputSchema().get("required");
        assertTrue(required.contains("name"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn -Dtest=DeleteMemoryToolTest test
```

Expected: FAIL — `DeleteMemoryTool` does not exist yet.

- [ ] **Step 3: Create `DeleteMemoryTool`**

```java
package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;
import org.example.agent.engine.MemoryStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class DeleteMemoryTool implements Tool {

    private final MemoryStore store;

    public DeleteMemoryTool(MemoryStore store) {
        this.store = store;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "delete_memory",
                "Delete a memory entry by name.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "Name of the memory entry to delete")
                        ),
                        "required", List.of("name")
                )
        );
    }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var name = (String) input.get("name");
        try {
            boolean deleted = store.delete(name);
            return ToolResultEnvelope.success(deleted ? "Memory deleted: " + name : "Memory not found: " + name);
        } catch (IOException e) {
            return ToolResultEnvelope.error("Error deleting memory: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```
mvn -Dtest=DeleteMemoryToolTest test
```

Expected: PASS (all 4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/DeleteMemoryTool.java \
        src/test/java/org/example/agent/tool/DeleteMemoryToolTest.java
git commit -m "feat(memory): add DeleteMemoryTool"
```

---

## Task 6: `QueryEngine` integration

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Create: `src/test/java/org/example/agent/engine/QueryEngineMemoryTest.java`

- [ ] **Step 1: Write the failing integration test**

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineMemoryTest {

    @TempDir
    Path workdir;

    @Test
    void memory_section_appears_in_system_prompt() throws IOException {
        var memoryDir = workdir.resolve(".memory");
        var store = new MemoryStore(memoryDir);
        store.save(new MemoryEntry("pref_tabs", "Use tabs not spaces", "user", "Always indent with tabs."));

        var capturedPrompt = new String[1];
        var engine = new QueryEngine(
                request -> {
                    capturedPrompt[0] = request.systemPrompt();
                    return new ModelResponse(
                            List.of(new ContentBlock.Text("done")),
                            StopReason.END_TURN, 10, 5
                    );
                },
                new ToolRegistry(),
                store
        );

        engine.run(new QueryParams(
                List.of(Message.user("hello")),
                "base system prompt",
                null, null, null
        ));

        assertNotNull(capturedPrompt[0]);
        assertTrue(capturedPrompt[0].contains("## Memories"),
                "Expected ## Memories header, got: " + capturedPrompt[0]);
        assertTrue(capturedPrompt[0].contains("pref_tabs [user]"),
                "Expected memory name and type");
        assertTrue(capturedPrompt[0].contains("Always indent with tabs."),
                "Expected memory content");
        assertTrue(capturedPrompt[0].contains("base system prompt"),
                "Base prompt must still be present");
    }

    @Test
    void no_memory_section_when_store_is_null() {
        var capturedPrompt = new String[1];
        var engine = new QueryEngine(
                request -> {
                    capturedPrompt[0] = request.systemPrompt();
                    return new ModelResponse(
                            List.of(new ContentBlock.Text("done")),
                            StopReason.END_TURN, 10, 5
                    );
                },
                new ToolRegistry()
        );

        engine.run(new QueryParams(
                List.of(Message.user("hello")),
                "just base",
                null, null, null
        ));

        assertEquals("just base", capturedPrompt[0]);
    }

    @Test
    void no_memory_section_when_memory_dir_is_empty() throws IOException {
        var store = new MemoryStore(workdir.resolve(".memory"));

        var capturedPrompt = new String[1];
        var engine = new QueryEngine(
                request -> {
                    capturedPrompt[0] = request.systemPrompt();
                    return new ModelResponse(
                            List.of(new ContentBlock.Text("done")),
                            StopReason.END_TURN, 10, 5
                    );
                },
                new ToolRegistry(),
                store
        );

        engine.run(new QueryParams(
                List.of(Message.user("hello")),
                "base only",
                null, null, null
        ));

        assertEquals("base only", capturedPrompt[0]);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn -Dtest=QueryEngineMemoryTest test
```

Expected: FAIL — no `QueryEngine(ModelClient, ToolRegistry, MemoryStore)` constructor exists yet.

- [ ] **Step 3: Modify `QueryEngine`**

Make the following changes to `src/main/java/org/example/agent/engine/QueryEngine.java`:

**3a. Add import** (after the existing imports):

```java
import org.example.agent.engine.MemoryStore;
import java.io.IOException;
```

(Note: `MemoryStore` is in the same package so no import is strictly required, but add `java.io.IOException` if not already present.)

**3b. Add the `memoryStore` field** (after `private final HookRunner hookRunner;`):

```java
    private final MemoryStore memoryStore;
```

**3c. Update the private constructor** — change the signature to add `MemoryStore memoryStore` as the last parameter, and assign it:

Replace:
```java
    private QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                        SkillRegistry skillRegistry, ContextCompactor compactor,
                        ExecutorService executor,
                        PermissionChecker permissionChecker, UserConfirmation userConfirmation,
                        HookRunner hookRunner) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.compactor = compactor;
        this.permissionChecker = permissionChecker;
        this.userConfirmation = userConfirmation;
        this.hookRunner = hookRunner;
```

With:
```java
    private QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                        SkillRegistry skillRegistry, ContextCompactor compactor,
                        ExecutorService executor,
                        PermissionChecker permissionChecker, UserConfirmation userConfirmation,
                        HookRunner hookRunner, MemoryStore memoryStore) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.compactor = compactor;
        this.permissionChecker = permissionChecker;
        this.userConfirmation = userConfirmation;
        this.hookRunner = hookRunner;
        this.memoryStore = memoryStore;
```

**3d. Update all existing delegating constructors** — append `, null` to each `this(...)` call. Replace each constructor body as follows:

```java
    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this(modelClient, toolRegistry, null, defaultCompactor(), ForkJoinPool.commonPool(), null, null, null, null);
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, ExecutorService executor) {
        this(modelClient, toolRegistry, null, defaultCompactor(), executor, null, null, null, null);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                       SkillRegistry skillRegistry) {
        this(modelClient, toolRegistry, skillRegistry, defaultCompactor(), ForkJoinPool.commonPool(), null, null, null, null);
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                ContextCompactor compactor, ExecutorService executor) {
        this(modelClient, toolRegistry, null, compactor, executor, null, null, null, null);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, UserConfirmation userConfirmation) {
        this(modelClient, toolRegistry, null, defaultCompactor(),
                ForkJoinPool.commonPool(), permissionChecker, userConfirmation, null, null);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, HookRunner hookRunner) {
        this(modelClient, toolRegistry, null, defaultCompactor(),
                ForkJoinPool.commonPool(), null, null, hookRunner, null);
    }
```

**3e. Add new public constructor** (after the `HookRunner` constructor):

```java
    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, MemoryStore memoryStore) {
        this(modelClient, toolRegistry, null, defaultCompactor(),
                ForkJoinPool.commonPool(), null, null, null, memoryStore);
    }
```

**3f. Replace `augmentSystemPrompt` and add `buildMemorySection`**:

Replace:
```java
    private String augmentSystemPrompt(String base) {
        if (skillRegistry == null) return base;
        var skillSection = skillRegistry.describeAvailable();
        if (skillSection.isEmpty()) return base;
        if (base == null || base.isEmpty()) return skillSection;
        return skillSection + "\n\n" + base;
    }
```

With:
```java
    private String augmentSystemPrompt(String base) {
        var result = base;
        if (skillRegistry != null) {
            var skillSection = skillRegistry.describeAvailable();
            if (!skillSection.isEmpty()) {
                result = (result == null || result.isEmpty()) ? skillSection : skillSection + "\n\n" + result;
            }
        }
        var memSection = buildMemorySection();
        if (!memSection.isEmpty()) {
            result = (result == null || result.isEmpty()) ? memSection : result + "\n\n" + memSection;
        }
        return result;
    }

    private String buildMemorySection() {
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
        } catch (IOException e) {
            return "";
        }
    }
```

- [ ] **Step 4: Run the integration tests to verify they pass**

```
mvn -Dtest=QueryEngineMemoryTest test
```

Expected: PASS (all 3 tests).

- [ ] **Step 5: Run the full test suite to check for regressions**

```
mvn test
```

Expected: all existing tests still PASS. If any test for `QueryEngineSkillTest` or `QueryEngineHookTest` fails, check that the existing constructors still compile and delegate correctly.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/engine/QueryEngineMemoryTest.java
git commit -m "feat(memory): integrate MemoryStore into QueryEngine system prompt"
```

---

## Final Verification

- [ ] Run the complete test suite one last time:

```
mvn test
```

Expected: all tests PASS, no compilation errors.
