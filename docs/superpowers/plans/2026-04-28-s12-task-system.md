# S12 Task System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent, dependency-aware task graph with four tools (task_create / task_update / task_get / task_list) and migrate the existing subagent runner out of the `tool/task/` namespace.

**Architecture:** TaskRecord (immutable data record) → TaskStore (file I/O + hand-rolled JSON) → TaskManager (business logic: create, update, complete with auto-unlock) → four thin Tool classes that parse input and call TaskManager via `ctx.taskManager()`. TaskManager is stored in ToolUseContext alongside PlanningState.

**Tech Stack:** Java 21, JUnit 5 `@TempDir`, `java.nio.file`, hand-rolled JSON (no external library)

**Maven test command:**
```bash
MVN=/c/Users/ThinkPad/.m2/wrapper/dists/apache-maven-3.9.5-bin/2adeog8mj13csp1uusqnc1f2mo/apache-maven-3.9.5/bin/mvn
cd D:/project/java/personal/agent && $MVN test -q
```

---

## File Map

**Create:**
- `src/main/java/org/example/agent/tool/subagent/SubagentTool.java` (moved from tool/task/TaskTool.java)
- `src/main/java/org/example/agent/tool/task/TaskStatus.java`
- `src/main/java/org/example/agent/tool/task/TaskRecord.java`
- `src/main/java/org/example/agent/tool/task/NoSuchTaskException.java`
- `src/main/java/org/example/agent/tool/task/TaskStore.java`
- `src/main/java/org/example/agent/tool/task/TaskPatch.java`
- `src/main/java/org/example/agent/tool/task/UpdateResult.java`
- `src/main/java/org/example/agent/tool/task/TaskManager.java`
- `src/main/java/org/example/agent/tool/task/TaskCreateTool.java`
- `src/main/java/org/example/agent/tool/task/TaskUpdateTool.java`
- `src/main/java/org/example/agent/tool/task/TaskGetTool.java`
- `src/main/java/org/example/agent/tool/task/TaskListTool.java`
- `src/test/java/org/example/agent/tool/subagent/SubagentToolTest.java`
- `src/test/java/org/example/agent/tool/task/TaskStoreTest.java`
- `src/test/java/org/example/agent/tool/task/TaskManagerTest.java`
- `src/test/java/org/example/agent/tool/task/TaskToolsIntegrationTest.java`

**Modify:**
- `src/main/java/org/example/agent/tool/ToolUseContext.java` — add `taskManager` field + `withTaskManager()`
- `src/main/java/org/example/agent/engine/QueryEngine.java` — wire TaskManager into default context
- `src/test/java/org/example/agent/tool/ToolUseContextTest.java` — add tests for `withTaskManager()`

**Delete:**
- `src/main/java/org/example/agent/tool/task/TaskTool.java`
- `src/test/java/org/example/agent/tool/task/TaskToolTest.java`

---

## Task 1: Migrate SubagentTool

Move the existing subagent runner out of `tool/task/` so its tool name `"subagent"` doesn't conflict with the new task management tools. The only change is class name and tool name — all internal logic stays identical.

**Files:**
- Create: `src/main/java/org/example/agent/tool/subagent/SubagentTool.java`
- Create: `src/test/java/org/example/agent/tool/subagent/SubagentToolTest.java`
- Delete: `src/main/java/org/example/agent/tool/task/TaskTool.java`
- Delete: `src/test/java/org/example/agent/tool/task/TaskToolTest.java`

- [ ] **Step 1.1: Create SubagentTool.java**

Create `src/main/java/org/example/agent/tool/subagent/SubagentTool.java`:

```java
package org.example.agent.tool.subagent;

import org.example.agent.core.*;
import org.example.agent.engine.QueryEngine;
import org.example.agent.engine.QueryResult;
import org.example.agent.model.ModelClient;
import org.example.agent.tool.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SubagentTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "subagent",
            "Run a subtask in a clean context and return a summary.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "prompt", Map.of("type", "string", "description", "The task to perform")
                    ),
                    "required", List.of("prompt")
            )
    );

    private final QueryEngine subEngine;
    private final int maxTurns;

    public SubagentTool(ModelClient modelClient, ToolRegistry subRegistry, int maxTurns) {
        this.subEngine = new QueryEngine(modelClient, subRegistry);
        this.maxTurns = maxTurns;
    }

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var raw = input.get("prompt");
        if (!(raw instanceof String prompt) || prompt.isBlank()) {
            return ToolResultEnvelope.error("prompt must be a non-blank string");
        }

        var params = new QueryParams(
                List.of(Message.user(prompt)),
                null, null, null,
                maxTurns
        );

        var result = subEngine.run(params);

        return switch (result) {
            case QueryResult.Failed f -> {
                var msg = f.cause().getMessage();
                yield ToolResultEnvelope.error(msg != null ? msg : f.cause().getClass().getSimpleName());
            }
            case QueryResult.Success s -> {
                var summary = s.messages().stream()
                        .filter(m -> m.role() == Role.ASSISTANT)
                        .reduce((a, b) -> b)
                        .map(m -> m.content().stream()
                                .filter(b -> b instanceof ContentBlock.Text)
                                .map(b -> ((ContentBlock.Text) b).text())
                                .collect(Collectors.joining()))
                        .filter(text -> !text.isBlank())
                        .orElse(null);
                if (summary == null) {
                    yield ToolResultEnvelope.error("subagent produced no output");
                }
                yield ToolResultEnvelope.success(summary);
            }
        };
    }
}
```

- [ ] **Step 1.2: Create SubagentToolTest.java**

Create `src/test/java/org/example/agent/tool/subagent/SubagentToolTest.java`:

```java
package org.example.agent.tool.subagent;

import org.example.agent.core.*;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubagentToolTest {

    @Test
    void definition_has_correct_name() {
        var tool = new SubagentTool(req -> null, new ToolRegistry(), 5);
        assertEquals("subagent", tool.definition().name());
    }

    @Test
    void is_not_concurrency_safe() {
        var tool = new SubagentTool(req -> null, new ToolRegistry(), 5);
        assertFalse(tool.isConcurrencySafe());
    }

    @Test
    void execute_returns_error_when_prompt_missing() {
        var tool = new SubagentTool(req -> null, new ToolRegistry(), 5);
        var result = tool.execute(Map.of(), ToolUseContext.defaults("."));
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void execute_returns_last_assistant_message_from_subagent() {
        var tool = new SubagentTool(
                request -> new ModelResponse(
                        List.of(new ContentBlock.Text("subagent result")),
                        StopReason.END_TURN, 10, 5),
                new ToolRegistry(),
                5
        );
        var result = tool.execute(Map.of("prompt", "do something"), ToolUseContext.defaults("."));
        assertTrue(result.ok());
        assertEquals("subagent result", result.content());
    }
}
```

- [ ] **Step 1.3: Delete old files**

Delete `src/main/java/org/example/agent/tool/task/TaskTool.java`.  
Delete `src/test/java/org/example/agent/tool/task/TaskToolTest.java`.

- [ ] **Step 1.4: Run tests to confirm green**

```bash
$MVN test -q
```

Expected: BUILD SUCCESS with no failures.

- [ ] **Step 1.5: Commit**

```bash
git add src/main/java/org/example/agent/tool/subagent/ \
        src/test/java/org/example/agent/tool/subagent/
git rm src/main/java/org/example/agent/tool/task/TaskTool.java \
       src/test/java/org/example/agent/tool/task/TaskToolTest.java
git commit -m "refactor(s12): migrate TaskTool → SubagentTool, free task namespace"
```

---

## Task 2: Data Model

Three small files: `TaskStatus` enum, `TaskRecord` immutable record, `NoSuchTaskException`. No tests needed — correctness is verified by the TaskStore and TaskManager tests that use them.

**Files:**
- Create: `src/main/java/org/example/agent/tool/task/TaskStatus.java`
- Create: `src/main/java/org/example/agent/tool/task/TaskRecord.java`
- Create: `src/main/java/org/example/agent/tool/task/NoSuchTaskException.java`

- [ ] **Step 2.1: Create TaskStatus.java**

```java
package org.example.agent.tool.task;

public enum TaskStatus {
    PENDING, IN_PROGRESS, COMPLETED, DELETED
}
```

- [ ] **Step 2.2: Create TaskRecord.java**

```java
package org.example.agent.tool.task;

import java.util.List;
import java.util.Objects;

public record TaskRecord(
        int id,
        String subject,
        String description,
        TaskStatus status,
        List<Integer> blockedBy,
        List<Integer> blocks,
        String owner
) {
    public TaskRecord {
        Objects.requireNonNull(subject,     "subject must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(status,      "status must not be null");
        Objects.requireNonNull(blockedBy,   "blockedBy must not be null");
        Objects.requireNonNull(blocks,      "blocks must not be null");
        Objects.requireNonNull(owner,       "owner must not be null");
        blockedBy = List.copyOf(blockedBy);
        blocks    = List.copyOf(blocks);
    }
}
```

- [ ] **Step 2.3: Create NoSuchTaskException.java**

```java
package org.example.agent.tool.task;

public class NoSuchTaskException extends RuntimeException {
    public NoSuchTaskException(int id) {
        super("Task #" + id + " not found");
    }
}
```

- [ ] **Step 2.4: Run tests**

```bash
$MVN test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 2.5: Commit**

```bash
git add src/main/java/org/example/agent/tool/task/TaskStatus.java \
        src/main/java/org/example/agent/tool/task/TaskRecord.java \
        src/main/java/org/example/agent/tool/task/NoSuchTaskException.java
git commit -m "feat(s12): add TaskRecord, TaskStatus, NoSuchTaskException"
```

---

## Task 3: TaskStore

File I/O layer: reads and writes `TaskRecord` as JSON files in a `.tasks/` directory. Uses hand-rolled JSON serialization — no external library. The `serialize` and `deserialize` methods are package-private so tests can verify them directly.

**Files:**
- Create: `src/main/java/org/example/agent/tool/task/TaskStore.java`
- Create: `src/test/java/org/example/agent/tool/task/TaskStoreTest.java`

- [ ] **Step 3.1: Write failing tests**

Create `src/test/java/org/example/agent/tool/task/TaskStoreTest.java`:

```java
package org.example.agent.tool.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskStoreTest {

    @TempDir Path tempDir;
    private TaskStore store;

    @BeforeEach
    void setUp() {
        store = new TaskStore(tempDir);
    }

    @Test
    void save_creates_file_and_load_returns_equal_record() {
        var task = new TaskRecord(1, "Write parser", "details", TaskStatus.PENDING,
                List.of(), List.of(2), "");
        store.save(task);

        assertTrue(Files.exists(tempDir.resolve("task_1.json")));
        var loaded = store.load(1);
        assertEquals(task, loaded);
    }

    @Test
    void load_throws_NoSuchTaskException_for_missing_id() {
        assertThrows(NoSuchTaskException.class, () -> store.load(99));
    }

    @Test
    void nextId_returns_1_when_directory_is_empty() {
        assertEquals(1, store.nextId());
    }

    @Test
    void nextId_returns_max_plus_one() throws IOException {
        Files.writeString(tempDir.resolve("task_1.json"),
                TaskStore.serialize(new TaskRecord(1, "A", "", TaskStatus.PENDING, List.of(), List.of(), "")));
        Files.writeString(tempDir.resolve("task_3.json"),
                TaskStore.serialize(new TaskRecord(3, "C", "", TaskStatus.PENDING, List.of(), List.of(), "")));

        assertEquals(4, store.nextId());
    }

    @Test
    void loadAll_returns_all_records_sorted_by_id() throws IOException {
        Files.writeString(tempDir.resolve("task_2.json"),
                TaskStore.serialize(new TaskRecord(2, "B", "", TaskStatus.IN_PROGRESS, List.of(), List.of(), "")));
        Files.writeString(tempDir.resolve("task_1.json"),
                TaskStore.serialize(new TaskRecord(1, "A", "", TaskStatus.PENDING, List.of(), List.of(), "")));

        var all = store.loadAll();
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).id());
        assertEquals(2, all.get(1).id());
    }

    @Test
    void serialize_roundtrips_all_fields() {
        var task = new TaskRecord(5, "my task", "desc with \"quotes\"",
                TaskStatus.COMPLETED, List.of(1, 2), List.of(3), "agent-x");
        var json = TaskStore.serialize(task);
        var loaded = TaskStore.deserialize(json);
        assertEquals(task, loaded);
    }

    @Test
    void serialize_handles_empty_lists() {
        var task = new TaskRecord(1, "t", "", TaskStatus.PENDING, List.of(), List.of(), "");
        var json = TaskStore.serialize(task);
        var loaded = TaskStore.deserialize(json);
        assertEquals(task, loaded);
    }
}
```

- [ ] **Step 3.2: Run test to confirm failure**

```bash
$MVN test -pl . -Dtest=TaskStoreTest -q 2>&1 | grep -E "ERROR|FAIL|cannot find"
```

Expected: compilation error — `TaskStore` doesn't exist yet.

- [ ] **Step 3.3: Create TaskStore.java**

```java
package org.example.agent.tool.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class TaskStore {

    private final Path tasksDir;

    TaskStore(Path tasksDir) {
        this.tasksDir = tasksDir;
        try {
            Files.createDirectories(tasksDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    TaskRecord load(int id) {
        var path = tasksDir.resolve("task_" + id + ".json");
        if (!Files.exists(path)) throw new NoSuchTaskException(id);
        try {
            return deserialize(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void save(TaskRecord record) {
        var path = tasksDir.resolve("task_" + record.id() + ".json");
        try {
            Files.writeString(path, serialize(record));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<TaskRecord> loadAll() {
        try {
            if (!Files.exists(tasksDir)) return List.of();
            try (var stream = Files.list(tasksDir)) {
                return stream
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.matches("task_\\d+\\.json"))
                        .map(name -> load(Integer.parseInt(
                                name.replace("task_", "").replace(".json", ""))))
                        .sorted(Comparator.comparingInt(TaskRecord::id))
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int nextId() {
        try {
            if (!Files.exists(tasksDir)) return 1;
            try (var stream = Files.list(tasksDir)) {
                return stream
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.matches("task_\\d+\\.json"))
                        .mapToInt(name -> Integer.parseInt(
                                name.replace("task_", "").replace(".json", "")))
                        .max()
                        .orElse(0) + 1;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // package-private for tests
    static String serialize(TaskRecord t) {
        return "{\n" +
                "  \"id\": " + t.id() + ",\n" +
                "  \"subject\": " + quote(t.subject()) + ",\n" +
                "  \"description\": " + quote(t.description()) + ",\n" +
                "  \"status\": \"" + t.status().name().toLowerCase() + "\",\n" +
                "  \"blockedBy\": " + intArray(t.blockedBy()) + ",\n" +
                "  \"blocks\": " + intArray(t.blocks()) + ",\n" +
                "  \"owner\": " + quote(t.owner()) + "\n" +
                "}";
    }

    // package-private for tests
    static TaskRecord deserialize(String json) {
        int id              = parseIntField(json, "id");
        String subject      = parseStringField(json, "subject");
        String description  = parseStringField(json, "description");
        TaskStatus status   = TaskStatus.valueOf(parseStringField(json, "status").toUpperCase());
        List<Integer> blockedBy = parseIntArray(json, "blockedBy");
        List<Integer> blocks    = parseIntArray(json, "blocks");
        String owner        = parseStringField(json, "owner");
        return new TaskRecord(id, subject, description, status, blockedBy, blocks, owner);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String intArray(List<Integer> list) {
        if (list.isEmpty()) return "[]";
        return "[" + list.stream().map(String::valueOf).collect(Collectors.joining(", ")) + "]";
    }

    private static int parseIntField(String json, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + field);
        return Integer.parseInt(m.group(1));
    }

    private static String parseStringField(String json, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + field);
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static List<Integer> parseIntArray(String json, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + field);
        String content = m.group(1).trim();
        if (content.isEmpty()) return new ArrayList<>();
        return Arrays.stream(content.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
```

- [ ] **Step 3.4: Run tests to confirm green**

```bash
$MVN test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 3.5: Commit**

```bash
git add src/main/java/org/example/agent/tool/task/TaskStore.java \
        src/test/java/org/example/agent/tool/task/TaskStoreTest.java
git commit -m "feat(s12): add TaskStore with hand-rolled JSON serialization"
```

---

## Task 4: TaskManager, TaskPatch, UpdateResult

Business logic layer. `TaskManager.create()` maintains bidirectional dependency links. `TaskManager.update()` merges a patch and, when status changes to COMPLETED, auto-unlocks downstream tasks. `isReady()` implements the Ready Rule from the spec.

**Files:**
- Create: `src/main/java/org/example/agent/tool/task/TaskPatch.java`
- Create: `src/main/java/org/example/agent/tool/task/UpdateResult.java`
- Create: `src/main/java/org/example/agent/tool/task/TaskManager.java`
- Create: `src/test/java/org/example/agent/tool/task/TaskManagerTest.java`

- [ ] **Step 4.1: Write failing tests**

Create `src/test/java/org/example/agent/tool/task/TaskManagerTest.java`:

```java
package org.example.agent.tool.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskManagerTest {

    @TempDir Path tempDir;
    private TaskManager manager;

    @BeforeEach
    void setUp() {
        manager = new TaskManager(tempDir);
    }

    @Test
    void create_assigns_sequential_ids() {
        var t1 = manager.create("A", "", List.of());
        var t2 = manager.create("B", "", List.of());
        assertEquals(1, t1.id());
        assertEquals(2, t2.id());
    }

    @Test
    void create_with_blockedBy_links_both_sides() {
        var t1 = manager.create("Parser", "", List.of());
        var t2 = manager.create("Checker", "", List.of(t1.id()));

        assertEquals(List.of(t1.id()), t2.blockedBy());
        assertEquals(List.of(t2.id()), manager.get(t1.id()).blocks());
    }

    @Test
    void create_throws_NoSuchTaskException_for_invalid_blockedBy() {
        assertThrows(NoSuchTaskException.class, () -> manager.create("X", "", List.of(99)));
    }

    @Test
    void isReady_true_when_pending_and_no_blockedBy() {
        var t = manager.create("Task", "", List.of());
        assertTrue(manager.isReady(t));
    }

    @Test
    void isReady_false_when_blockedBy_is_not_empty() {
        var t1 = manager.create("A", "", List.of());
        var t2 = manager.create("B", "", List.of(t1.id()));
        assertFalse(manager.isReady(t2));
    }

    @Test
    void complete_auto_unlocks_downstream_tasks() {
        var t1 = manager.create("A", "", List.of());
        var t2 = manager.create("B", "", List.of(t1.id()));

        var result = manager.update(t1.id(),
                new TaskPatch(TaskStatus.COMPLETED, null, null, null, List.of(), List.of()));

        assertEquals(List.of(t2.id()), result.unblocked());
        assertTrue(manager.isReady(manager.get(t2.id())));
    }

    @Test
    void complete_is_idempotent() {
        var t = manager.create("A", "", List.of());
        var patch = new TaskPatch(TaskStatus.COMPLETED, null, null, null, List.of(), List.of());

        manager.update(t.id(), patch);
        assertDoesNotThrow(() -> manager.update(t.id(), patch));
    }

    @Test
    void update_changes_only_provided_fields() {
        var t = manager.create("Original", "desc", List.of());
        var result = manager.update(t.id(),
                new TaskPatch(TaskStatus.IN_PROGRESS, null, null, "agent-x", List.of(), List.of()));

        var updated = result.task();
        assertEquals("Original", updated.subject());       // unchanged
        assertEquals("desc", updated.description());       // unchanged
        assertEquals(TaskStatus.IN_PROGRESS, updated.status());
        assertEquals("agent-x", updated.owner());
    }

    @Test
    void update_throws_NoSuchTaskException_for_missing_id() {
        assertThrows(NoSuchTaskException.class, () ->
                manager.update(99, new TaskPatch(null, null, null, null, List.of(), List.of())));
    }

    @Test
    void list_returns_all_tasks_sorted_by_id() {
        manager.create("A", "", List.of());
        manager.create("B", "", List.of());
        var list = manager.list();
        assertEquals(2, list.size());
        assertEquals(1, list.get(0).id());
        assertEquals(2, list.get(1).id());
    }
}
```

- [ ] **Step 4.2: Run test to confirm failure**

```bash
$MVN test -pl . -Dtest=TaskManagerTest -q 2>&1 | grep -E "ERROR|FAIL|cannot find"
```

Expected: compilation error — `TaskManager`, `TaskPatch`, `UpdateResult` don't exist yet.

- [ ] **Step 4.3: Create TaskPatch.java**

```java
package org.example.agent.tool.task;

import java.util.List;
import java.util.Objects;

public record TaskPatch(
        TaskStatus status,          // null = no change
        String subject,             // null = no change
        String description,         // null = no change
        String owner,               // null = no change
        List<Integer> addBlockedBy,
        List<Integer> addBlocks
) {
    public TaskPatch {
        Objects.requireNonNull(addBlockedBy, "addBlockedBy must not be null");
        Objects.requireNonNull(addBlocks,    "addBlocks must not be null");
        addBlockedBy = List.copyOf(addBlockedBy);
        addBlocks    = List.copyOf(addBlocks);
    }
}
```

- [ ] **Step 4.4: Create UpdateResult.java**

```java
package org.example.agent.tool.task;

import java.util.List;

public record UpdateResult(TaskRecord task, List<Integer> unblocked) {}
```

- [ ] **Step 4.5: Create TaskManager.java**

```java
package org.example.agent.tool.task;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TaskManager {

    private final TaskStore store;

    public TaskManager(Path tasksDir) {
        this.store = new TaskStore(tasksDir);
    }

    public TaskRecord create(String subject, String description, List<Integer> blockedBy) {
        Objects.requireNonNull(subject, "subject must not be null");
        if (subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        Objects.requireNonNull(description, "description must not be null");

        for (int bid : blockedBy) store.load(bid); // validate all exist

        int id = store.nextId();
        var record = new TaskRecord(id, subject, description, TaskStatus.PENDING,
                new ArrayList<>(blockedBy), new ArrayList<>(), "");
        store.save(record);

        for (int bid : blockedBy) {
            var blocker = store.load(bid);
            var newBlocks = new ArrayList<>(blocker.blocks());
            if (!newBlocks.contains(id)) newBlocks.add(id);
            store.save(new TaskRecord(blocker.id(), blocker.subject(), blocker.description(),
                    blocker.status(), blocker.blockedBy(), newBlocks, blocker.owner()));
        }

        return record;
    }

    public UpdateResult update(int id, TaskPatch patch) {
        var task = store.load(id);

        for (int bid : patch.addBlockedBy()) store.load(bid);
        for (int bid : patch.addBlocks())    store.load(bid);

        var newSubject     = patch.subject()     != null ? patch.subject()     : task.subject();
        var newDescription = patch.description() != null ? patch.description() : task.description();
        var newOwner       = patch.owner()       != null ? patch.owner()       : task.owner();
        var newStatus      = patch.status()      != null ? patch.status()      : task.status();

        var newBlockedBy = new ArrayList<>(task.blockedBy());
        for (int bid : patch.addBlockedBy()) if (!newBlockedBy.contains(bid)) newBlockedBy.add(bid);

        var newBlocks = new ArrayList<>(task.blocks());
        for (int bid : patch.addBlocks()) if (!newBlocks.contains(bid)) newBlocks.add(bid);

        // maintain bidirectional links for addBlockedBy
        for (int bid : patch.addBlockedBy()) {
            var blocker = store.load(bid);
            var bBlocks = new ArrayList<>(blocker.blocks());
            if (!bBlocks.contains(id)) {
                bBlocks.add(id);
                store.save(new TaskRecord(blocker.id(), blocker.subject(), blocker.description(),
                        blocker.status(), blocker.blockedBy(), bBlocks, blocker.owner()));
            }
        }

        // maintain bidirectional links for addBlocks
        for (int bid : patch.addBlocks()) {
            var blockee = store.load(bid);
            var bBlockedBy = new ArrayList<>(blockee.blockedBy());
            if (!bBlockedBy.contains(id)) {
                bBlockedBy.add(id);
                store.save(new TaskRecord(blockee.id(), blockee.subject(), blockee.description(),
                        blockee.status(), bBlockedBy, blockee.blocks(), blockee.owner()));
            }
        }

        var updated = new TaskRecord(id, newSubject, newDescription, newStatus,
                newBlockedBy, newBlocks, newOwner);
        store.save(updated);

        List<Integer> unblocked = List.of();
        if (newStatus == TaskStatus.COMPLETED && task.status() != TaskStatus.COMPLETED) {
            unblocked = autoUnlock(updated);
        }

        return new UpdateResult(updated, unblocked);
    }

    public TaskRecord get(int id) {
        return store.load(id);
    }

    public List<TaskRecord> list() {
        return store.loadAll();
    }

    public boolean isReady(TaskRecord t) {
        return t.status() == TaskStatus.PENDING && t.blockedBy().isEmpty();
    }

    private List<Integer> autoUnlock(TaskRecord completed) {
        var unblocked = new ArrayList<Integer>();
        for (int blockeeId : completed.blocks()) {
            try {
                var blockee = store.load(blockeeId);
                var newBlockedBy = new ArrayList<>(blockee.blockedBy());
                newBlockedBy.remove(Integer.valueOf(completed.id()));
                var updated = new TaskRecord(blockee.id(), blockee.subject(), blockee.description(),
                        blockee.status(), newBlockedBy, blockee.blocks(), blockee.owner());
                store.save(updated);
                if (isReady(updated)) unblocked.add(blockeeId);
            } catch (NoSuchTaskException ignored) {
                // blockee deleted, skip
            }
        }
        return List.copyOf(unblocked);
    }
}
```

- [ ] **Step 4.6: Run tests to confirm green**

```bash
$MVN test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 4.7: Commit**

```bash
git add src/main/java/org/example/agent/tool/task/TaskPatch.java \
        src/main/java/org/example/agent/tool/task/UpdateResult.java \
        src/main/java/org/example/agent/tool/task/TaskManager.java \
        src/test/java/org/example/agent/tool/task/TaskManagerTest.java
git commit -m "feat(s12): add TaskManager with create/update/auto-unlock/isReady"
```

---

## Task 5: Wire TaskManager into ToolUseContext and QueryEngine

Add `taskManager` field to `ToolUseContext` following the same builder pattern as `planningState`. Update `QueryEngine.run()` to automatically inject a `TaskManager` scoped to `{cwd}/.tasks`.

**Files:**
- Modify: `src/main/java/org/example/agent/tool/ToolUseContext.java`
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Modify: `src/test/java/org/example/agent/tool/ToolUseContextTest.java`

- [ ] **Step 5.1: Write failing test for withTaskManager()**

Add to `src/test/java/org/example/agent/tool/ToolUseContextTest.java`:

```java
// add this import at the top of the test file:
// import org.example.agent.tool.task.TaskManager;
// import java.nio.file.Path;

@Test
void defaults_creates_null_task_manager() {
    var ctx = ToolUseContext.defaults("/workspace");
    assertNull(ctx.taskManager());
}

@Test
void withTaskManager_returns_new_context_with_task_manager() {
    var ctx = ToolUseContext.defaults(".");
    var manager = new TaskManager(Path.of(System.getProperty("java.io.tmpdir"), "test-tasks-" + System.nanoTime()));
    var updated = ctx.withTaskManager(manager);
    assertSame(manager, updated.taskManager());
    assertSame(ctx.planningState(), updated.planningState()); // other fields unchanged
}
```

- [ ] **Step 5.2: Run test to confirm failure**

```bash
$MVN test -pl . -Dtest=ToolUseContextTest -q 2>&1 | grep -E "ERROR|FAIL|cannot find"
```

Expected: compilation error — `taskManager()` and `withTaskManager()` don't exist yet.

- [ ] **Step 5.3: Update ToolUseContext.java**

Replace the full contents of `src/main/java/org/example/agent/tool/ToolUseContext.java` with:

```java
package org.example.agent.tool;

import org.example.agent.hook.HookRunner;
import org.example.agent.permission.PermissionChecker;
import org.example.agent.permission.UserConfirmation;
import org.example.agent.tool.task.TaskManager;
import org.example.agent.tool.todo.PlanningState;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ToolUseContext {

    private final Map<String, Object> permissionContext;
    private final Map<String, Object> mcpClients;
    private final Map<String, Object> appState;
    private final List<String> notifications;
    private final String cwd;
    private final PlanningState planningState;
    private final TaskManager taskManager;
    private final PermissionChecker permissionChecker;
    private final UserConfirmation userConfirmation;
    private final HookRunner hookRunner;

    private ToolUseContext(Map<String, Object> permissionContext,
                           Map<String, Object> mcpClients,
                           Map<String, Object> appState,
                           List<String> notifications,
                           String cwd,
                           PlanningState planningState,
                           TaskManager taskManager,
                           PermissionChecker permissionChecker,
                           UserConfirmation userConfirmation,
                           HookRunner hookRunner) {
        this.permissionContext = permissionContext;
        this.mcpClients = mcpClients;
        this.appState = appState;
        this.notifications = notifications;
        this.cwd = cwd;
        this.planningState = planningState;
        this.taskManager = taskManager;
        this.permissionChecker = permissionChecker;
        this.userConfirmation = userConfirmation;
        this.hookRunner = hookRunner;
    }

    public static ToolUseContext defaults(String cwd) {
        Objects.requireNonNull(cwd, "cwd must not be null");
        return new ToolUseContext(Map.of(), Map.of(), Map.of(), List.of(), cwd,
                new PlanningState(), null, null, null, null);
    }

    public Map<String, Object> permissionContext() { return permissionContext; }
    public Map<String, Object> mcpClients()        { return mcpClients; }
    public Map<String, Object> appState()          { return appState; }
    public List<String>        notifications()     { return notifications; }
    public String              cwd()               { return cwd; }
    public PlanningState       planningState()     { return planningState; }
    public TaskManager         taskManager()       { return taskManager; }
    public PermissionChecker   permissionChecker() { return permissionChecker; }
    public UserConfirmation    userConfirmation()  { return userConfirmation; }
    public HookRunner          hookRunner()        { return hookRunner; }

    public ToolUseContext withNotifications(List<String> notifications) {
        Objects.requireNonNull(notifications, "notifications must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                List.copyOf(notifications), cwd, planningState, taskManager,
                permissionChecker, userConfirmation, hookRunner);
    }

    public ToolUseContext withTaskManager(TaskManager manager) {
        Objects.requireNonNull(manager, "taskManager must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, manager,
                permissionChecker, userConfirmation, hookRunner);
    }

    public ToolUseContext withPermissions(PermissionChecker checker, UserConfirmation confirmation) {
        Objects.requireNonNull(checker,      "checker must not be null");
        Objects.requireNonNull(confirmation, "confirmation must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, taskManager, checker, confirmation, hookRunner);
    }

    public ToolUseContext withHookRunner(HookRunner runner) {
        Objects.requireNonNull(runner, "hookRunner must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, taskManager,
                permissionChecker, userConfirmation, runner);
    }
}
```

- [ ] **Step 5.4: Update QueryEngine.run() to inject TaskManager**

In `src/main/java/org/example/agent/engine/QueryEngine.java`, find the `run()` method. After `currentCtx = ctx;` (currently line ~131), add the TaskManager injection:

```java
// in run(), after:  currentCtx = ctx;
// add:
var taskManagerPath = java.nio.file.Paths.get(System.getProperty("user.dir"), ".tasks");
ctx = ctx.withTaskManager(new org.example.agent.tool.task.TaskManager(taskManagerPath));
currentCtx = ctx;
```

The relevant section of `run()` should look like this after the change:

```java
public QueryResult run(QueryParams params) {
    currentState = QueryState.from(params);
    var baseCtx = ToolUseContext.defaults(System.getProperty("user.dir"));
    var ctx = baseCtx;
    if (permissionChecker != null) ctx = ctx.withPermissions(permissionChecker, userConfirmation);
    if (hookRunner != null) ctx = ctx.withHookRunner(hookRunner);
    var taskManagerPath = java.nio.file.Paths.get(System.getProperty("user.dir"), ".tasks");
    ctx = ctx.withTaskManager(new org.example.agent.tool.task.TaskManager(taskManagerPath));
    currentCtx = ctx;
    // ... rest of run() unchanged
```

- [ ] **Step 5.5: Run tests to confirm green**

```bash
$MVN test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 5.6: Commit**

```bash
git add src/main/java/org/example/agent/tool/ToolUseContext.java \
        src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/tool/ToolUseContextTest.java
git commit -m "feat(s12): add taskManager to ToolUseContext; wire into QueryEngine"
```

---

## Task 6: TaskCreateTool

**Files:**
- Create: `src/main/java/org/example/agent/tool/task/TaskCreateTool.java`
- Create: `src/test/java/org/example/agent/tool/task/TaskToolsIntegrationTest.java` (start this file here, add to it in Tasks 7-9)

- [ ] **Step 6.1: Write failing tests**

Create `src/test/java/org/example/agent/tool/task/TaskToolsIntegrationTest.java`:

```java
package org.example.agent.tool.task;

import org.example.agent.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskToolsIntegrationTest {

    @TempDir Path tempDir;
    private ToolUseContext ctx;
    private TaskManager manager;

    @BeforeEach
    void setUp() {
        manager = new TaskManager(tempDir);
        ctx = ToolUseContext.defaults(tempDir.toString()).withTaskManager(manager);
    }

    // ── TaskCreateTool ──────────────────────────────────────────────────────

    @Test
    void create_returns_success_with_id_and_subject() {
        var tool = new TaskCreateTool();
        var result = tool.execute(Map.of("subject", "Write parser"), ctx);
        assertTrue(result.ok());
        assertEquals("Created task #1: Write parser", result.content());
    }

    @Test
    void create_returns_error_when_subject_blank() {
        var tool = new TaskCreateTool();
        var result = tool.execute(Map.of("subject", "  "), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void create_returns_error_when_subject_missing() {
        var tool = new TaskCreateTool();
        var result = tool.execute(Map.of(), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void create_returns_error_for_invalid_blockedBy_id() {
        var tool = new TaskCreateTool();
        var result = tool.execute(Map.of("subject", "X", "blockedBy", List.of(99)), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
        assertTrue(result.content().contains("99"));
    }

    @Test
    void create_definition_has_correct_name() {
        assertEquals("task_create", new TaskCreateTool().definition().name());
    }
}
```

- [ ] **Step 6.2: Run test to confirm failure**

```bash
$MVN test -pl . -Dtest=TaskToolsIntegrationTest -q 2>&1 | grep -E "ERROR|FAIL|cannot find"
```

Expected: compilation error — `TaskCreateTool` doesn't exist yet.

- [ ] **Step 6.3: Create TaskCreateTool.java**

```java
package org.example.agent.tool.task;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TaskCreateTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "task_create",
            "Create a new task. Optionally declare dependencies via blockedBy.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "subject", Map.of("type", "string", "description", "One-line task title"),
                            "description", Map.of("type", "string", "description", "Additional details"),
                            "blockedBy", Map.of("type", "array", "items", Map.of("type", "integer"),
                                    "description", "IDs of tasks that must complete first")
                    ),
                    "required", List.of("subject")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        Object rawSubject = input.get("subject");
        if (!(rawSubject instanceof String subject) || subject.isBlank()) {
            return ToolResultEnvelope.error("subject must not be blank");
        }
        String description = (String) input.getOrDefault("description", "");
        if (description == null) description = "";

        List<Integer> blockedBy;
        try {
            var raw = (List<Object>) input.getOrDefault("blockedBy", List.of());
            blockedBy = raw.stream().map(o -> ((Number) o).intValue()).collect(Collectors.toList());
        } catch (ClassCastException e) {
            return ToolResultEnvelope.error("blockedBy must be an array of integers");
        }

        var taskManager = ctx.taskManager();
        if (taskManager == null) return ToolResultEnvelope.error("task manager not configured");

        try {
            var task = taskManager.create(subject, description, blockedBy);
            return ToolResultEnvelope.success("Created task #" + task.id() + ": " + task.subject());
        } catch (NoSuchTaskException e) {
            return ToolResultEnvelope.error(e.getMessage());
        } catch (UncheckedIOException e) {
            return ToolResultEnvelope.error("IO error: " + e.getCause().getMessage());
        }
    }
}
```

- [ ] **Step 6.4: Run tests to confirm green**

```bash
$MVN test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 6.5: Commit**

```bash
git add src/main/java/org/example/agent/tool/task/TaskCreateTool.java \
        src/test/java/org/example/agent/tool/task/TaskToolsIntegrationTest.java
git commit -m "feat(s12): add TaskCreateTool"
```

---

## Task 7: TaskUpdateTool

**Files:**
- Create: `src/main/java/org/example/agent/tool/task/TaskUpdateTool.java`
- Modify: `src/test/java/org/example/agent/tool/task/TaskToolsIntegrationTest.java`

- [ ] **Step 7.1: Add failing tests**

Append to `TaskToolsIntegrationTest.java` (inside the class, after the create tests):

```java
    // ── TaskUpdateTool ──────────────────────────────────────────────────────

    @Test
    void update_returns_updated_message() {
        manager.create("Task A", "", List.of());
        var tool = new TaskUpdateTool();
        var result = tool.execute(Map.of("id", 1, "status", "in_progress"), ctx);
        assertTrue(result.ok());
        assertEquals("Updated task #1", result.content());
    }

    @Test
    void update_complete_returns_unblocked_list() {
        manager.create("Task A", "", List.of());
        manager.create("Task B", "", List.of(1));
        var tool = new TaskUpdateTool();
        var result = tool.execute(Map.of("id", 1, "status", "completed"), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("unblocked"));
        assertTrue(result.content().contains("2"));
    }

    @Test
    void update_returns_error_for_missing_id() {
        var tool = new TaskUpdateTool();
        var result = tool.execute(Map.of("id", 99, "status", "completed"), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void update_returns_error_for_invalid_status() {
        manager.create("Task A", "", List.of());
        var tool = new TaskUpdateTool();
        var result = tool.execute(Map.of("id", 1, "status", "bogus"), ctx);
        assertFalse(result.ok());
        assertTrue(result.content().contains("bogus"));
    }

    @Test
    void update_definition_has_correct_name() {
        assertEquals("task_update", new TaskUpdateTool().definition().name());
    }
```

- [ ] **Step 7.2: Run test to confirm failure**

```bash
$MVN test -pl . -Dtest=TaskToolsIntegrationTest -q 2>&1 | grep -E "ERROR|FAIL|cannot find"
```

Expected: compilation error — `TaskUpdateTool` doesn't exist yet.

- [ ] **Step 7.3: Create TaskUpdateTool.java**

```java
package org.example.agent.tool.task;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TaskUpdateTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "task_update",
            "Update a task. Only provided fields change. addBlockedBy/addBlocks append to existing dependency lists.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "id",           Map.of("type", "integer"),
                            "status",       Map.of("type", "string",
                                    "enum", List.of("pending", "in_progress", "completed", "deleted")),
                            "subject",      Map.of("type", "string"),
                            "description",  Map.of("type", "string"),
                            "owner",        Map.of("type", "string"),
                            "addBlockedBy", Map.of("type", "array", "items", Map.of("type", "integer")),
                            "addBlocks",    Map.of("type", "array", "items", Map.of("type", "integer"))
                    ),
                    "required", List.of("id")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        Object rawId = input.get("id");
        if (!(rawId instanceof Number)) return ToolResultEnvelope.error("id must be an integer");
        int id = ((Number) rawId).intValue();

        TaskStatus status = null;
        if (input.containsKey("status")) {
            status = switch ((String) input.get("status")) {
                case "pending"     -> TaskStatus.PENDING;
                case "in_progress" -> TaskStatus.IN_PROGRESS;
                case "completed"   -> TaskStatus.COMPLETED;
                case "deleted"     -> TaskStatus.DELETED;
                default            -> null;
            };
            if (status == null) return ToolResultEnvelope.error("Unknown status: " + input.get("status"));
        }

        String subject     = (String) input.get("subject");
        String description = (String) input.get("description");
        String owner       = (String) input.get("owner");
        List<Integer> addBlockedBy = toIntList((List<Object>) input.getOrDefault("addBlockedBy", List.of()));
        List<Integer> addBlocks    = toIntList((List<Object>) input.getOrDefault("addBlocks",    List.of()));

        var taskManager = ctx.taskManager();
        if (taskManager == null) return ToolResultEnvelope.error("task manager not configured");

        try {
            var result = taskManager.update(id,
                    new TaskPatch(status, subject, description, owner, addBlockedBy, addBlocks));
            if (!result.unblocked().isEmpty()) {
                return ToolResultEnvelope.success(
                        "Completed task #" + id + "; unblocked: " + result.unblocked());
            }
            return ToolResultEnvelope.success("Updated task #" + id);
        } catch (NoSuchTaskException e) {
            return ToolResultEnvelope.error(e.getMessage());
        } catch (UncheckedIOException e) {
            return ToolResultEnvelope.error("IO error: " + e.getCause().getMessage());
        }
    }

    private static List<Integer> toIntList(List<Object> raw) {
        return raw.stream().map(o -> ((Number) o).intValue()).collect(Collectors.toList());
    }
}
```

- [ ] **Step 7.4: Run tests to confirm green**

```bash
$MVN test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 7.5: Commit**

```bash
git add src/main/java/org/example/agent/tool/task/TaskUpdateTool.java \
        src/test/java/org/example/agent/tool/task/TaskToolsIntegrationTest.java
git commit -m "feat(s12): add TaskUpdateTool"
```

---

## Task 8: TaskGetTool

**Files:**
- Create: `src/main/java/org/example/agent/tool/task/TaskGetTool.java`
- Modify: `src/test/java/org/example/agent/tool/task/TaskToolsIntegrationTest.java`

- [ ] **Step 8.1: Add failing tests**

Append to `TaskToolsIntegrationTest.java`:

```java
    // ── TaskGetTool ─────────────────────────────────────────────────────────

    @Test
    void get_returns_task_details() {
        manager.create("Write parser", "some desc", List.of());
        var tool = new TaskGetTool();
        var result = tool.execute(Map.of("id", 1), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("#1"));
        assertTrue(result.content().contains("Write parser"));
        assertTrue(result.content().contains("some desc"));
    }

    @Test
    void get_returns_error_for_missing_id() {
        var tool = new TaskGetTool();
        var result = tool.execute(Map.of("id", 99), ctx);
        assertFalse(result.ok());
        assertTrue(result.content().contains("99"));
    }

    @Test
    void get_definition_has_correct_name() {
        assertEquals("task_get", new TaskGetTool().definition().name());
    }
```

- [ ] **Step 8.2: Run test to confirm failure**

```bash
$MVN test -pl . -Dtest=TaskToolsIntegrationTest -q 2>&1 | grep -E "ERROR|FAIL|cannot find"
```

Expected: compilation error — `TaskGetTool` doesn't exist yet.

- [ ] **Step 8.3: Create TaskGetTool.java**

```java
package org.example.agent.tool.task;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

public class TaskGetTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "task_get",
            "Get details of a single task by id.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "id", Map.of("type", "integer", "description", "Task ID")
                    ),
                    "required", List.of("id")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        Object rawId = input.get("id");
        if (!(rawId instanceof Number)) return ToolResultEnvelope.error("id must be an integer");
        int id = ((Number) rawId).intValue();

        var taskManager = ctx.taskManager();
        if (taskManager == null) return ToolResultEnvelope.error("task manager not configured");

        try {
            return ToolResultEnvelope.success(format(taskManager.get(id)));
        } catch (NoSuchTaskException e) {
            return ToolResultEnvelope.error(e.getMessage());
        } catch (UncheckedIOException e) {
            return ToolResultEnvelope.error("IO error: " + e.getCause().getMessage());
        }
    }

    // package-private for reuse in TaskListTool
    static String format(TaskRecord t) {
        var sb = new StringBuilder();
        sb.append("#").append(t.id())
          .append(" [").append(t.status().name().toLowerCase()).append("] ")
          .append(t.subject()).append("\n");
        sb.append("blockedBy: ").append(t.blockedBy())
          .append("  blocks: ").append(t.blocks())
          .append("  owner: \"").append(t.owner()).append("\"");
        if (!t.description().isBlank()) {
            sb.append("\n").append(t.description());
        }
        return sb.toString();
    }
}
```

- [ ] **Step 8.4: Run tests to confirm green**

```bash
$MVN test -q
```

Expected: BUILD SUCCESS.

- [ ] **Step 8.5: Commit**

```bash
git add src/main/java/org/example/agent/tool/task/TaskGetTool.java \
        src/test/java/org/example/agent/tool/task/TaskToolsIntegrationTest.java
git commit -m "feat(s12): add TaskGetTool"
```

---

## Task 9: TaskListTool

**Files:**
- Create: `src/main/java/org/example/agent/tool/task/TaskListTool.java`
- Modify: `src/test/java/org/example/agent/tool/task/TaskToolsIntegrationTest.java`

- [ ] **Step 9.1: Add failing tests**

Append to `TaskToolsIntegrationTest.java`:

```java
    // ── TaskListTool ────────────────────────────────────────────────────────

    @Test
    void list_shows_all_tasks_with_status_markers() {
        manager.create("Write parser", "", List.of());
        manager.create("Write tests", "", List.of(1));
        var tool = new TaskListTool();
        var result = tool.execute(Map.of(), ctx);
        assertTrue(result.ok());
        String out = result.content();
        assertTrue(out.contains("#1"));
        assertTrue(out.contains("#2"));
        // task 1 is ready (pending, no blockedBy) — marked with *
        assertTrue(out.contains("pending*"));
        // task 2 is blocked — no *
        assertFalse(out.contains("#2") && out.substring(out.indexOf("#2")).contains("pending*"));
    }

    @Test
    void list_returns_no_tasks_message_when_empty() {
        var result = new TaskListTool().execute(Map.of(), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("no tasks"));
    }

    @Test
    void list_definition_has_correct_name() {
        assertEquals("task_list", new TaskListTool().definition().name());
    }
```

- [ ] **Step 9.2: Run test to confirm failure**

```bash
$MVN test -pl . -Dtest=TaskToolsIntegrationTest -q 2>&1 | grep -E "ERROR|FAIL|cannot find"
```

Expected: compilation error — `TaskListTool` doesn't exist yet.

- [ ] **Step 9.3: Create TaskListTool.java**

```java
package org.example.agent.tool.task;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

public class TaskListTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "task_list",
            "List all tasks. Ready tasks (pending with no blockedBy) are marked with *.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of()
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var taskManager = ctx.taskManager();
        if (taskManager == null) return ToolResultEnvelope.error("task manager not configured");

        try {
            var tasks = taskManager.list();
            if (tasks.isEmpty()) return ToolResultEnvelope.success("(no tasks)");

            var sb = new StringBuilder();
            for (var t : tasks) {
                String statusLabel = t.status().name().toLowerCase();
                if (taskManager.isReady(t)) statusLabel += "*";
                sb.append(String.format("#%-3d [%-13s] %s", t.id(), statusLabel, t.subject()));
                if (!t.owner().isBlank()) sb.append("    owner: ").append(t.owner());
                sb.append("\n");
            }
            return ToolResultEnvelope.success(sb.toString().stripTrailing());
        } catch (UncheckedIOException e) {
            return ToolResultEnvelope.error("IO error: " + e.getCause().getMessage());
        }
    }
}
```

- [ ] **Step 9.4: Run full test suite to confirm all green**

```bash
$MVN test -q
```

Expected: BUILD SUCCESS with zero failures.

- [ ] **Step 9.5: Commit**

```bash
git add src/main/java/org/example/agent/tool/task/TaskListTool.java \
        src/test/java/org/example/agent/tool/task/TaskToolsIntegrationTest.java
git commit -m "feat(s12): add TaskListTool; complete s12 task system"
```

---

## Self-Review Checklist

- [x] **Spec coverage:** All spec requirements covered — TaskRecord ✓, bidirectional dependency ✓, auto-unlock on complete ✓, isReady rule ✓, 4 tools ✓, SubagentTool migration ✓, hand-rolled JSON ✓, error handling ✓, @TempDir tests ✓
- [x] **No placeholders:** All steps contain complete code
- [x] **Type consistency:** `TaskPatch`, `UpdateResult`, `TaskRecord`, `TaskStatus`, `NoSuchTaskException` defined in Task 2-4 and used consistently in Tasks 5-9. `TaskGetTool.format()` is package-private as noted.
- [x] **Dependency order:** Data model → Store → Manager → Context → Tools — each task only uses types defined in earlier tasks.
