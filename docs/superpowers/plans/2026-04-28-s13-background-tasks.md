# S13 Background Tasks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent 框架添加后台任务系统，使模型可以启动慢操作（Shell 命令或 Java Callable），主循环继续推进，结果通过通知队列在下一轮带回模型。

**Architecture:** 新增 `BackgroundManager`（持有 `ExecutorService` + 通知队列），通过 `ToolUseContext.withBackgroundManager()` 注入，与 s12 TaskManager 完全对称。四个工具（run/check/list/cancel）供模型调用。QueryEngine 主循环每轮开始前调用 `drainAndInject()` 注入已完成的通知。

**Tech Stack:** Java 21, JUnit 5 (`@TempDir`), `ExecutorService`/`Executors.newCachedThreadPool()`, `ReentrantLock`, `ProcessBuilder`, `java.util.concurrent.Future`

---

## File Map

**新建（main）**
```
src/main/java/org/example/agent/tool/background/
  RuntimeTaskStatus.java
  RuntimeTaskRecord.java
  BackgroundNotification.java
  RuntimeTaskStore.java
  BackgroundTask.java
  ShellBackgroundTask.java
  CallableBackgroundTask.java
  BackgroundManager.java
  BackgroundRunTool.java
  BackgroundCheckTool.java
  BackgroundListTool.java
  BackgroundCancelTool.java
```

**新建（test）**
```
src/test/java/org/example/agent/tool/background/
  RuntimeTaskStoreTest.java
  BackgroundManagerTest.java
  BackgroundToolsIntegrationTest.java
src/test/java/org/example/agent/engine/
  QueryEngineBackgroundTest.java
```

**修改**
```
src/main/java/org/example/agent/tool/ToolUseContext.java
src/main/java/org/example/agent/engine/QueryEngine.java
```

---

## Task 1: 数据结构（RuntimeTaskStatus、RuntimeTaskRecord、BackgroundNotification）

**Files:**
- Create: `src/main/java/org/example/agent/tool/background/RuntimeTaskStatus.java`
- Create: `src/main/java/org/example/agent/tool/background/RuntimeTaskRecord.java`
- Create: `src/main/java/org/example/agent/tool/background/BackgroundNotification.java`

- [ ] **Step 1: 创建 RuntimeTaskStatus**

```java
package org.example.agent.tool.background;

public enum RuntimeTaskStatus {
    RUNNING, COMPLETED, FAILED, TIMEOUT, CANCELLED
}
```

- [ ] **Step 2: 创建 RuntimeTaskRecord**

```java
package org.example.agent.tool.background;

import java.nio.file.Path;
import java.util.Objects;

public record RuntimeTaskRecord(
        String id,
        String description,
        RuntimeTaskStatus status,
        long startedAt,
        String resultPreview,
        Path outputFile
) {
    public RuntimeTaskRecord {
        Objects.requireNonNull(id,            "id must not be null");
        Objects.requireNonNull(description,   "description must not be null");
        Objects.requireNonNull(status,        "status must not be null");
        Objects.requireNonNull(resultPreview, "resultPreview must not be null");
        Objects.requireNonNull(outputFile,    "outputFile must not be null");
    }
}
```

- [ ] **Step 3: 创建 BackgroundNotification**

```java
package org.example.agent.tool.background;

import java.util.Objects;

public record BackgroundNotification(
        String taskId,
        String description,
        RuntimeTaskStatus status,
        String preview
) {
    public BackgroundNotification {
        Objects.requireNonNull(taskId,      "taskId must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(status,      "status must not be null");
        Objects.requireNonNull(preview,     "preview must not be null");
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn compile -q
```

期望：BUILD SUCCESS，无错误。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/background/RuntimeTaskStatus.java \
        src/main/java/org/example/agent/tool/background/RuntimeTaskRecord.java \
        src/main/java/org/example/agent/tool/background/BackgroundNotification.java
git commit -m "feat(s13): add RuntimeTaskStatus, RuntimeTaskRecord, BackgroundNotification"
```

---

## Task 2: RuntimeTaskStore

**Files:**
- Create: `src/main/java/org/example/agent/tool/background/RuntimeTaskStore.java`
- Test: `src/test/java/org/example/agent/tool/background/RuntimeTaskStoreTest.java`

- [ ] **Step 1: 写失败测试**

```java
package org.example.agent.tool.background;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeTaskStoreTest {

    @TempDir Path tempDir;
    private RuntimeTaskStore store;

    @BeforeEach
    void setUp() {
        store = new RuntimeTaskStore(tempDir);
    }

    @Test
    void save_creates_json_file_and_load_returns_equal_record() {
        var record = new RuntimeTaskRecord("a1b2c3d4", "pytest -v",
                RuntimeTaskStatus.COMPLETED, 1710000000000L,
                "5 passed", tempDir.resolve("a1b2c3d4.log"));
        store.save(record);

        assertTrue(java.nio.file.Files.exists(tempDir.resolve("a1b2c3d4.json")));
        var loaded = store.load("a1b2c3d4");
        assertEquals(record, loaded);
    }

    @Test
    void load_returns_null_for_missing_id() {
        assertNull(store.load("nonexistent"));
    }

    @Test
    void loadAll_returns_all_saved_records() {
        var r1 = new RuntimeTaskRecord("aaa00001", "cmd1", RuntimeTaskStatus.RUNNING,
                1000L, "", tempDir.resolve("aaa00001.log"));
        var r2 = new RuntimeTaskRecord("bbb00002", "cmd2", RuntimeTaskStatus.COMPLETED,
                2000L, "ok", tempDir.resolve("bbb00002.log"));
        store.save(r1);
        store.save(r2);

        var all = store.loadAll();
        assertEquals(2, all.size());
    }

    @Test
    void serialize_roundtrips_all_fields() {
        var record = new RuntimeTaskRecord("a1b2c3d4", "npm install",
                RuntimeTaskStatus.FAILED, 1710000000000L,
                "error: package not found",
                Paths.get("/tmp/.runtime-tasks/a1b2c3d4.log"));
        var json = RuntimeTaskStore.serialize(record);
        var loaded = RuntimeTaskStore.deserialize(json);
        assertEquals(record, loaded);
    }

    @Test
    void serialize_escapes_quotes_in_description() {
        var record = new RuntimeTaskRecord("a1b2c3d4", "echo \"hello\"",
                RuntimeTaskStatus.COMPLETED, 0L, "", tempDir.resolve("a1b2c3d4.log"));
        var json = RuntimeTaskStore.serialize(record);
        var loaded = RuntimeTaskStore.deserialize(json);
        assertEquals("echo \"hello\"", loaded.description());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=RuntimeTaskStoreTest -q
```

期望：编译失败或 ClassNotFoundException（RuntimeTaskStore 尚未创建）。

- [ ] **Step 3: 实现 RuntimeTaskStore**

```java
package org.example.agent.tool.background;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class RuntimeTaskStore {

    private final Path dir;

    RuntimeTaskStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void save(RuntimeTaskRecord record) {
        var path = dir.resolve(record.id() + ".json");
        try {
            Files.writeString(path, serialize(record));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    RuntimeTaskRecord load(String id) {
        var path = dir.resolve(id + ".json");
        if (!Files.exists(path)) return null;
        try {
            return deserialize(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<RuntimeTaskRecord> loadAll() {
        try {
            if (!Files.exists(dir)) return List.of();
            try (var stream = Files.list(dir)) {
                return stream
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.endsWith(".json"))
                        .map(name -> load(name.replace(".json", "")))
                        .filter(r -> r != null)
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // package-private for tests
    static String serialize(RuntimeTaskRecord r) {
        return "{\n" +
                "  \"id\": " + quote(r.id()) + ",\n" +
                "  \"description\": " + quote(r.description()) + ",\n" +
                "  \"status\": \"" + r.status().name().toLowerCase() + "\",\n" +
                "  \"startedAt\": " + r.startedAt() + ",\n" +
                "  \"resultPreview\": " + quote(r.resultPreview()) + ",\n" +
                "  \"outputFile\": " + quote(r.outputFile().toString()) + "\n" +
                "}";
    }

    // package-private for tests
    static RuntimeTaskRecord deserialize(String json) {
        String id            = parseStringField(json, "id");
        String description   = parseStringField(json, "description");
        RuntimeTaskStatus status = RuntimeTaskStatus.valueOf(
                parseStringField(json, "status").toUpperCase());
        long startedAt       = parseLongField(json, "startedAt");
        String resultPreview = parseStringField(json, "resultPreview");
        Path outputFile      = Paths.get(parseStringField(json, "outputFile"));
        return new RuntimeTaskRecord(id, description, status, startedAt, resultPreview, outputFile);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String parseStringField(String json, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + field);
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static long parseLongField(String json, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + field);
        return Long.parseLong(m.group(1));
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn test -Dtest=RuntimeTaskStoreTest -q
```

期望：5 tests passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/background/RuntimeTaskStore.java \
        src/test/java/org/example/agent/tool/background/RuntimeTaskStoreTest.java
git commit -m "feat(s13): add RuntimeTaskStore with hand-rolled JSON serialization"
```

---

## Task 3: BackgroundTask 接口 + ShellBackgroundTask + CallableBackgroundTask

**Files:**
- Create: `src/main/java/org/example/agent/tool/background/BackgroundTask.java`
- Create: `src/main/java/org/example/agent/tool/background/ShellBackgroundTask.java`
- Create: `src/main/java/org/example/agent/tool/background/CallableBackgroundTask.java`
- Test: `src/test/java/org/example/agent/tool/background/BackgroundManagerTest.java`（前几个测试）

- [ ] **Step 1: 写失败测试（ShellBackgroundTask 执行 echo）**

```java
package org.example.agent.tool.background;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundManagerTest {

    @TempDir Path tempDir;

    @Test
    void shell_task_writes_output_to_file() throws Exception {
        var outFile = tempDir.resolve("out.log");
        var task = new ShellBackgroundTask("echo hello_world", 10);
        task.execute(outFile);
        var content = Files.readString(outFile);
        assertTrue(content.contains("hello_world"));
    }

    @Test
    void shell_task_preview_returns_first_500_chars() throws Exception {
        var outFile = tempDir.resolve("out.log");
        Files.writeString(outFile, "a".repeat(600));
        var task = new ShellBackgroundTask("echo x", 10);
        var preview = task.preview(outFile);
        assertEquals(503, preview.length()); // 500 + "..."
        assertTrue(preview.endsWith("..."));
    }

    @Test
    void callable_task_writes_return_value_to_file() throws Exception {
        var outFile = tempDir.resolve("out.log");
        var task = new CallableBackgroundTask("compute", () -> "result_42");
        task.execute(outFile);
        assertEquals("result_42", Files.readString(outFile).trim());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=BackgroundManagerTest#shell_task_writes_output_to_file+shell_task_preview_returns_first_500_chars+callable_task_writes_return_value_to_file -q
```

期望：编译失败（类未定义）。

- [ ] **Step 3: 实现 BackgroundTask 接口**

```java
package org.example.agent.tool.background;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public interface BackgroundTask {

    String describe();

    void execute(Path outputFile) throws Exception;

    default String preview(Path outputFile) throws IOException {
        var bytes = Files.readAllBytes(outputFile);
        var full = new String(bytes, StandardCharsets.UTF_8);
        return full.length() <= 500 ? full : full.substring(0, 500) + "...";
    }
}
```

- [ ] **Step 4: 实现 ShellBackgroundTask**

```java
package org.example.agent.tool.background;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ShellBackgroundTask implements BackgroundTask {

    private final String command;
    private final int timeoutSeconds;

    public ShellBackgroundTask(String command, int timeoutSeconds) {
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String describe() { return command; }

    @Override
    public void execute(Path outputFile) throws Exception {
        var process = new ProcessBuilder("bash", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TimeoutException("Command timed out after " + timeoutSeconds + "s");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
```

- [ ] **Step 5: 实现 CallableBackgroundTask**

```java
package org.example.agent.tool.background;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

public class CallableBackgroundTask implements BackgroundTask {

    private final String description;
    private final Callable<String> callable;

    public CallableBackgroundTask(String description, Callable<String> callable) {
        this.description = description;
        this.callable = callable;
    }

    @Override
    public String describe() { return description; }

    @Override
    public void execute(Path outputFile) throws Exception {
        var result = callable.call();
        Files.writeString(outputFile, result != null ? result : "", StandardCharsets.UTF_8);
    }
}
```

- [ ] **Step 6: 运行测试，确认通过**

```bash
mvn test -Dtest=BackgroundManagerTest#shell_task_writes_output_to_file+shell_task_preview_returns_first_500_chars+callable_task_writes_return_value_to_file -q
```

期望：3 tests passed。

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/agent/tool/background/BackgroundTask.java \
        src/main/java/org/example/agent/tool/background/ShellBackgroundTask.java \
        src/main/java/org/example/agent/tool/background/CallableBackgroundTask.java \
        src/test/java/org/example/agent/tool/background/BackgroundManagerTest.java
git commit -m "feat(s13): add BackgroundTask interface, ShellBackgroundTask, CallableBackgroundTask"
```

---

## Task 4: BackgroundManager

**Files:**
- Create: `src/main/java/org/example/agent/tool/background/BackgroundManager.java`
- Test: `src/test/java/org/example/agent/tool/background/BackgroundManagerTest.java`（追加更多测试）

- [ ] **Step 1: 在 BackgroundManagerTest 中追加测试**

在已有的 `BackgroundManagerTest.java` 文件末尾、类的 `}` 之前，追加以下测试方法：

```java
    @Test
    void submit_returns_id_and_task_is_initially_running() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new ShellBackgroundTask("sleep 10", 30));
        var record = manager.check(id);
        assertNotNull(record);
        assertEquals(RuntimeTaskStatus.RUNNING, record.status());
        manager.shutdown();
    }

    @Test
    void completed_task_appears_in_drain() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new ShellBackgroundTask("echo done", 10));
        waitForStatus(manager, id, RuntimeTaskStatus.COMPLETED, 3000);
        var notifications = manager.drain();
        assertEquals(1, notifications.size());
        assertEquals(id, notifications.get(0).taskId());
        assertEquals(RuntimeTaskStatus.COMPLETED, notifications.get(0).status());
        assertTrue(notifications.get(0).preview().contains("done"));
        manager.shutdown();
    }

    @Test
    void drain_clears_queue_on_second_call() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new ShellBackgroundTask("echo x", 10));
        waitForStatus(manager, id, RuntimeTaskStatus.COMPLETED, 3000);
        manager.drain();
        assertTrue(manager.drain().isEmpty());
        manager.shutdown();
    }

    @Test
    void failed_task_appears_in_drain_with_failed_status() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new CallableBackgroundTask("fail", () -> {
            throw new RuntimeException("intentional failure");
        }));
        waitForStatus(manager, id, RuntimeTaskStatus.FAILED, 3000);
        var notifications = manager.drain();
        assertEquals(1, notifications.size());
        assertEquals(RuntimeTaskStatus.FAILED, notifications.get(0).status());
        manager.shutdown();
    }

    @Test
    void list_returns_all_submitted_tasks() throws Exception {
        var manager = new BackgroundManager(tempDir);
        manager.submit(new ShellBackgroundTask("echo a", 10));
        manager.submit(new ShellBackgroundTask("echo b", 10));
        var list = manager.list();
        assertEquals(2, list.size());
        manager.shutdown();
    }

    @Test
    void cancel_cancels_running_task() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new ShellBackgroundTask("sleep 30", 60));
        Thread.sleep(100); // 确保任务已启动
        var cancelled = manager.cancel(id);
        assertTrue(cancelled);
        assertEquals(RuntimeTaskStatus.CANCELLED, manager.check(id).status());
        manager.shutdown();
    }

    @Test
    void cancel_returns_false_for_completed_task() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new ShellBackgroundTask("echo x", 10));
        waitForStatus(manager, id, RuntimeTaskStatus.COMPLETED, 3000);
        assertFalse(manager.cancel(id));
        manager.shutdown();
    }

    private void waitForStatus(BackgroundManager manager, String id,
                                RuntimeTaskStatus expected, long timeoutMs)
            throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var record = manager.check(id);
            if (record != null && record.status() == expected) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for status " + expected +
                ", current: " + manager.check(id).status());
    }
```

- [ ] **Step 2: 运行新测试，确认失败**

```bash
mvn test -Dtest=BackgroundManagerTest#submit_returns_id_and_task_is_initially_running -q
```

期望：编译失败（BackgroundManager 未定义）。

- [ ] **Step 3: 实现 BackgroundManager**

```java
package org.example.agent.tool.background;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class BackgroundManager {

    private final Path runtimeDir;
    private final ExecutorService executor;
    private final Map<String, Future<?>> futures = new HashMap<>();
    private final Map<String, RuntimeTaskRecord> records = new HashMap<>();
    private final List<BackgroundNotification> notificationQueue = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final RuntimeTaskStore store;

    public BackgroundManager(Path runtimeDir) {
        this.runtimeDir = runtimeDir;
        this.store = new RuntimeTaskStore(runtimeDir);
        this.executor = Executors.newCachedThreadPool(r -> {
            var t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    public String submit(BackgroundTask task) {
        var id = newId();
        var outputFile = runtimeDir.resolve(id + ".log");
        var record = new RuntimeTaskRecord(id, task.describe(), RuntimeTaskStatus.RUNNING,
                System.currentTimeMillis(), "", outputFile);
        lock.lock();
        try {
            records.put(id, record);
            store.save(record);
        } finally {
            lock.unlock();
        }
        var future = executor.submit(() -> {
            try {
                task.execute(outputFile);
                var preview = task.preview(outputFile);
                updateRecord(id, RuntimeTaskStatus.COMPLETED, preview);
            } catch (TimeoutException e) {
                updateRecord(id, RuntimeTaskStatus.TIMEOUT, "Command timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // cancelled externally — record already updated by cancel()
            } catch (Exception e) {
                var msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                updateRecord(id, RuntimeTaskStatus.FAILED, msg);
            }
            return null;
        });
        lock.lock();
        try {
            futures.put(id, future);
        } finally {
            lock.unlock();
        }
        return id;
    }

    public RuntimeTaskRecord check(String id) {
        lock.lock();
        try {
            return records.get(id);
        } finally {
            lock.unlock();
        }
    }

    public List<RuntimeTaskRecord> list() {
        lock.lock();
        try {
            return records.values().stream()
                    .sorted(Comparator.comparingLong(RuntimeTaskRecord::startedAt))
                    .collect(Collectors.toList());
        } finally {
            lock.unlock();
        }
    }

    public boolean cancel(String id) {
        lock.lock();
        try {
            var record = records.get(id);
            if (record == null || record.status() != RuntimeTaskStatus.RUNNING) return false;
            var future = futures.get(id);
            if (future != null) future.cancel(true);
            var cancelled = new RuntimeTaskRecord(id, record.description(),
                    RuntimeTaskStatus.CANCELLED, record.startedAt(), "", record.outputFile());
            records.put(id, cancelled);
            store.save(cancelled);
            notificationQueue.add(new BackgroundNotification(
                    id, record.description(), RuntimeTaskStatus.CANCELLED, ""));
            return true;
        } finally {
            lock.unlock();
        }
    }

    public List<BackgroundNotification> drain() {
        lock.lock();
        try {
            var result = List.copyOf(notificationQueue);
            notificationQueue.clear();
            return result;
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private void updateRecord(String id, RuntimeTaskStatus status, String preview) {
        lock.lock();
        try {
            var current = records.get(id);
            if (current == null || current.status() != RuntimeTaskStatus.RUNNING) return;
            var updated = new RuntimeTaskRecord(id, current.description(), status,
                    current.startedAt(), preview, current.outputFile());
            records.put(id, updated);
            store.save(updated);
            notificationQueue.add(new BackgroundNotification(
                    id, current.description(), status, preview));
        } finally {
            lock.unlock();
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
```

- [ ] **Step 4: 运行所有 BackgroundManagerTest，确认通过**

```bash
mvn test -Dtest=BackgroundManagerTest -q
```

期望：10 tests passed（含 Task 3 的 3 个 + Task 4 的 7 个）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/background/BackgroundManager.java \
        src/test/java/org/example/agent/tool/background/BackgroundManagerTest.java
git commit -m "feat(s13): add BackgroundManager with ExecutorService and notification queue"
```

---

## Task 5: ToolUseContext 扩展

**Files:**
- Modify: `src/main/java/org/example/agent/tool/ToolUseContext.java`
- Test: `src/test/java/org/example/agent/tool/ToolUseContextTest.java`（追加测试）

- [ ] **Step 1: 查看现有 ToolUseContextTest 末尾，追加测试**

```java
    @Test
    void withBackgroundManager_returns_new_context_with_manager() {
        var ctx = ToolUseContext.defaults("/tmp");
        var manager = new org.example.agent.tool.background.BackgroundManager(
                java.nio.file.Paths.get("/tmp/rt"));
        var updated = ctx.withBackgroundManager(manager);
        assertSame(manager, updated.backgroundManager());
        assertNull(ctx.backgroundManager());
        manager.shutdown();
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=ToolUseContextTest#withBackgroundManager_returns_new_context_with_manager -q
```

期望：编译失败（方法未定义）。

- [ ] **Step 3: 修改 ToolUseContext.java**

在 `import` 区域添加：
```java
import org.example.agent.tool.background.BackgroundManager;
```

在现有字段列表末尾（`private final HookRunner hookRunner;` 之后）追加：
```java
    private final BackgroundManager backgroundManager;
```

修改私有构造函数签名，追加参数 `BackgroundManager backgroundManager`，并在构造函数体内赋值：
```java
    private ToolUseContext(Map<String, Object> permissionContext,
                           Map<String, Object> mcpClients,
                           Map<String, Object> appState,
                           List<String> notifications,
                           String cwd,
                           PlanningState planningState,
                           TaskManager taskManager,
                           PermissionChecker permissionChecker,
                           UserConfirmation userConfirmation,
                           HookRunner hookRunner,
                           BackgroundManager backgroundManager) {
        this.permissionContext    = permissionContext;
        this.mcpClients           = mcpClients;
        this.appState             = appState;
        this.notifications        = notifications;
        this.cwd                  = cwd;
        this.planningState        = planningState;
        this.taskManager          = taskManager;
        this.permissionChecker    = permissionChecker;
        this.userConfirmation     = userConfirmation;
        this.hookRunner           = hookRunner;
        this.backgroundManager    = backgroundManager;
    }
```

修改 `defaults()` 工厂方法，末尾追加 `null`：
```java
    public static ToolUseContext defaults(String cwd) {
        Objects.requireNonNull(cwd, "cwd must not be null");
        return new ToolUseContext(Map.of(), Map.of(), Map.of(), List.of(), cwd,
                new PlanningState(), null, null, null, null, null);
    }
```

所有现有的 `with*` 方法都需要在 `new ToolUseContext(...)` 调用末尾追加 `backgroundManager`（传递原值）：

`withNotifications`：
```java
    public ToolUseContext withNotifications(List<String> notifications) {
        Objects.requireNonNull(notifications, "notifications must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                List.copyOf(notifications), cwd, planningState, taskManager,
                permissionChecker, userConfirmation, hookRunner, backgroundManager);
    }
```

`withTaskManager`：
```java
    public ToolUseContext withTaskManager(TaskManager manager) {
        Objects.requireNonNull(manager, "taskManager must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, manager,
                permissionChecker, userConfirmation, hookRunner, backgroundManager);
    }
```

`withPermissions`：
```java
    public ToolUseContext withPermissions(PermissionChecker checker, UserConfirmation confirmation) {
        Objects.requireNonNull(checker,      "checker must not be null");
        Objects.requireNonNull(confirmation, "confirmation must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, taskManager, checker, confirmation,
                hookRunner, backgroundManager);
    }
```

`withHookRunner`：
```java
    public ToolUseContext withHookRunner(HookRunner runner) {
        Objects.requireNonNull(runner, "hookRunner must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, taskManager,
                permissionChecker, userConfirmation, runner, backgroundManager);
    }
```

在 `withHookRunner` 之后追加新方法：
```java
    public ToolUseContext withBackgroundManager(BackgroundManager manager) {
        Objects.requireNonNull(manager, "backgroundManager must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, taskManager,
                permissionChecker, userConfirmation, hookRunner, manager);
    }
```

在 getter 区域追加：
```java
    public BackgroundManager backgroundManager() { return backgroundManager; }
```

- [ ] **Step 4: 运行所有测试，确认通过**

```bash
mvn test -q
```

期望：BUILD SUCCESS，所有已有测试继续通过，新测试通过。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/ToolUseContext.java \
        src/test/java/org/example/agent/tool/ToolUseContextTest.java
git commit -m "feat(s13): add BackgroundManager field and withBackgroundManager to ToolUseContext"
```

---

## Task 6: BackgroundRunTool

**Files:**
- Create: `src/main/java/org/example/agent/tool/background/BackgroundRunTool.java`
- Test: `src/test/java/org/example/agent/tool/background/BackgroundToolsIntegrationTest.java`

- [ ] **Step 1: 写失败测试**

```java
package org.example.agent.tool.background;

import org.example.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundToolsIntegrationTest {

    @TempDir Path tempDir;
    private BackgroundManager manager;
    private ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        manager = new BackgroundManager(tempDir);
        ctx = ToolUseContext.defaults(tempDir.toString()).withBackgroundManager(manager);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    // ── BackgroundRunTool ────────────────────────────────────────────────────

    @Test
    void run_returns_started_message_with_id() {
        var tool = new BackgroundRunTool();
        var result = tool.execute(Map.of("command", "echo hello"), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().startsWith("Started background task ["));
        assertTrue(result.content().contains("echo hello"));
    }

    @Test
    void run_uses_default_timeout_300() {
        var tool = new BackgroundRunTool();
        var result = tool.execute(Map.of("command", "echo x"), ctx);
        assertTrue(result.ok());
    }

    @Test
    void run_returns_error_when_command_missing() {
        var tool = new BackgroundRunTool();
        var result = tool.execute(Map.of(), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void run_returns_error_when_manager_not_configured() {
        var ctxNoManager = ToolUseContext.defaults(tempDir.toString());
        var result = new BackgroundRunTool().execute(Map.of("command", "echo x"), ctxNoManager);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void run_definition_has_correct_name() {
        assertEquals("background_run", new BackgroundRunTool().definition().name());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=BackgroundToolsIntegrationTest#run_returns_started_message_with_id -q
```

期望：编译失败（BackgroundRunTool 未定义）。

- [ ] **Step 3: 实现 BackgroundRunTool**

```java
package org.example.agent.tool.background;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

public class BackgroundRunTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "background_run",
            "Start a shell command in the background. Returns a task_id immediately.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "command", Map.of("type", "string",
                                    "description", "Shell command to run (e.g. 'pytest -v')"),
                            "timeout", Map.of("type", "integer",
                                    "description", "Timeout in seconds (default 300)")
                    ),
                    "required", List.of("command")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var raw = input.get("command");
        if (!(raw instanceof String command) || command.isBlank()) {
            return ToolResultEnvelope.error("command must not be blank");
        }
        int timeout = ((Number) input.getOrDefault("timeout", 300)).intValue();

        var manager = ctx.backgroundManager();
        if (manager == null) return ToolResultEnvelope.error("background manager not configured");

        var id = manager.submit(new ShellBackgroundTask(command, timeout));
        return ToolResultEnvelope.success("Started background task [" + id + "]: " + command);
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn test -Dtest=BackgroundToolsIntegrationTest#run_returns_started_message_with_id+run_uses_default_timeout_300+run_returns_error_when_command_missing+run_returns_error_when_manager_not_configured+run_definition_has_correct_name -q
```

期望：5 tests passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/background/BackgroundRunTool.java \
        src/test/java/org/example/agent/tool/background/BackgroundToolsIntegrationTest.java
git commit -m "feat(s13): add BackgroundRunTool"
```

---

## Task 7: BackgroundCheckTool

**Files:**
- Create: `src/main/java/org/example/agent/tool/background/BackgroundCheckTool.java`
- Test: `src/test/java/org/example/agent/tool/background/BackgroundToolsIntegrationTest.java`（追加）

- [ ] **Step 1: 在 BackgroundToolsIntegrationTest 末尾追加测试**

```java
    // ── BackgroundCheckTool ──────────────────────────────────────────────────

    @Test
    void check_running_task_shows_running_status() throws InterruptedException {
        var id = manager.submit(new ShellBackgroundTask("sleep 10", 30));
        var tool = new BackgroundCheckTool();
        var result = tool.execute(Map.of("id", id), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("RUNNING"));
        assertTrue(result.content().contains(id));
    }

    @Test
    void check_completed_task_shows_preview_and_log_path() throws InterruptedException {
        var id = manager.submit(new ShellBackgroundTask("echo finished", 10));
        waitForStatus(id, RuntimeTaskStatus.COMPLETED, 3000);
        var tool = new BackgroundCheckTool();
        var result = tool.execute(Map.of("id", id), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("COMPLETED"));
        assertTrue(result.content().contains("finished"));
        assertTrue(result.content().contains(".log"));
    }

    @Test
    void check_returns_error_for_unknown_id() {
        var tool = new BackgroundCheckTool();
        var result = tool.execute(Map.of("id", "notexist"), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void check_definition_has_correct_name() {
        assertEquals("background_check", new BackgroundCheckTool().definition().name());
    }

    private void waitForStatus(String id, RuntimeTaskStatus expected, long timeoutMs)
            throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var record = manager.check(id);
            if (record != null && record.status() == expected) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + expected);
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=BackgroundToolsIntegrationTest#check_running_task_shows_running_status -q
```

期望：编译失败。

- [ ] **Step 3: 实现 BackgroundCheckTool**

```java
package org.example.agent.tool.background;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

public class BackgroundCheckTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "background_check",
            "Check the status of a background task by its id.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "id", Map.of("type", "string", "description", "Task id returned by background_run")
                    ),
                    "required", List.of("id")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var id = (String) input.get("id");
        if (id == null || id.isBlank()) return ToolResultEnvelope.error("id must not be blank");

        var manager = ctx.backgroundManager();
        if (manager == null) return ToolResultEnvelope.error("background manager not configured");

        var record = manager.check(id);
        if (record == null) return ToolResultEnvelope.error("No background task with id: " + id);

        long elapsedSec = (System.currentTimeMillis() - record.startedAt()) / 1000;

        if (record.status() == RuntimeTaskStatus.RUNNING) {
            return ToolResultEnvelope.success(
                    "[" + id + "] RUNNING - started " + elapsedSec + "s ago: " + record.description());
        }

        return ToolResultEnvelope.success(
                "[" + id + "] " + record.status() + " - " + record.description() + "\n" +
                "Preview: " + record.resultPreview() + "\n" +
                "Full output: .runtime-tasks/" + id + ".log");
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn test -Dtest=BackgroundToolsIntegrationTest#check_running_task_shows_running_status+check_completed_task_shows_preview_and_log_path+check_returns_error_for_unknown_id+check_definition_has_correct_name -q
```

期望：4 tests passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/background/BackgroundCheckTool.java \
        src/test/java/org/example/agent/tool/background/BackgroundToolsIntegrationTest.java
git commit -m "feat(s13): add BackgroundCheckTool"
```

---

## Task 8: BackgroundListTool

**Files:**
- Create: `src/main/java/org/example/agent/tool/background/BackgroundListTool.java`
- Test: 追加到 `BackgroundToolsIntegrationTest.java`

- [ ] **Step 1: 追加测试**

```java
    // ── BackgroundListTool ───────────────────────────────────────────────────

    @Test
    void list_shows_all_submitted_tasks() throws InterruptedException {
        var id1 = manager.submit(new ShellBackgroundTask("echo aaa", 10));
        var id2 = manager.submit(new ShellBackgroundTask("sleep 10", 30));
        var tool = new BackgroundListTool();
        var result = tool.execute(Map.of(), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains(id1));
        assertTrue(result.content().contains(id2));
        assertTrue(result.content().contains("echo aaa"));
        assertTrue(result.content().contains("sleep 10"));
    }

    @Test
    void list_returns_no_tasks_message_when_empty() {
        var result = new BackgroundListTool().execute(Map.of(), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("no background tasks"));
    }

    @Test
    void list_definition_has_correct_name() {
        assertEquals("background_list", new BackgroundListTool().definition().name());
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=BackgroundToolsIntegrationTest#list_shows_all_submitted_tasks -q
```

期望：编译失败。

- [ ] **Step 3: 实现 BackgroundListTool**

```java
package org.example.agent.tool.background;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.Map;

public class BackgroundListTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "background_list",
            "List all background tasks and their current status.",
            Map.of("type", "object", "properties", Map.of())
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var manager = ctx.backgroundManager();
        if (manager == null) return ToolResultEnvelope.error("background manager not configured");

        var records = manager.list();
        if (records.isEmpty()) return ToolResultEnvelope.success("no background tasks");

        var sb = new StringBuilder();
        for (var r : records) {
            long elapsedSec = (System.currentTimeMillis() - r.startedAt()) / 1000;
            String marker = switch (r.status()) {
                case COMPLETED -> "✓";
                case FAILED, TIMEOUT -> "✗";
                case CANCELLED -> "✕";
                default -> " ";
            };
            sb.append(String.format("[%s] %-10s %-30s (%ds) %s%n",
                    r.id(), r.status(), r.description(), elapsedSec, marker));
        }
        return ToolResultEnvelope.success(sb.toString().trim());
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn test -Dtest=BackgroundToolsIntegrationTest#list_shows_all_submitted_tasks+list_returns_no_tasks_message_when_empty+list_definition_has_correct_name -q
```

期望：3 tests passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/background/BackgroundListTool.java \
        src/test/java/org/example/agent/tool/background/BackgroundToolsIntegrationTest.java
git commit -m "feat(s13): add BackgroundListTool"
```

---

## Task 9: BackgroundCancelTool

**Files:**
- Create: `src/main/java/org/example/agent/tool/background/BackgroundCancelTool.java`
- Test: 追加到 `BackgroundToolsIntegrationTest.java`

- [ ] **Step 1: 追加测试**

```java
    // ── BackgroundCancelTool ─────────────────────────────────────────────────

    @Test
    void cancel_returns_cancelled_message() throws InterruptedException {
        var id = manager.submit(new ShellBackgroundTask("sleep 30", 60));
        Thread.sleep(100);
        var tool = new BackgroundCancelTool();
        var result = tool.execute(Map.of("id", id), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("cancelled"));
        assertTrue(result.content().contains(id));
    }

    @Test
    void cancel_already_completed_returns_not_cancellable() throws InterruptedException {
        var id = manager.submit(new ShellBackgroundTask("echo x", 10));
        waitForStatus(id, RuntimeTaskStatus.COMPLETED, 3000);
        var tool = new BackgroundCancelTool();
        var result = tool.execute(Map.of("id", id), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("not cancellable"));
        assertTrue(result.content().contains("COMPLETED"));
    }

    @Test
    void cancel_returns_error_for_unknown_id() {
        var result = new BackgroundCancelTool().execute(Map.of("id", "nosuchid"), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void cancel_definition_has_correct_name() {
        assertEquals("background_cancel", new BackgroundCancelTool().definition().name());
    }
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=BackgroundToolsIntegrationTest#cancel_returns_cancelled_message -q
```

期望：编译失败。

- [ ] **Step 3: 实现 BackgroundCancelTool**

```java
package org.example.agent.tool.background;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

public class BackgroundCancelTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "background_cancel",
            "Cancel a running background task.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "id", Map.of("type", "string", "description", "Task id to cancel")
                    ),
                    "required", List.of("id")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var id = (String) input.get("id");
        if (id == null || id.isBlank()) return ToolResultEnvelope.error("id must not be blank");

        var manager = ctx.backgroundManager();
        if (manager == null) return ToolResultEnvelope.error("background manager not configured");

        var record = manager.check(id);
        if (record == null) return ToolResultEnvelope.error("No background task with id: " + id);

        if (record.status() != RuntimeTaskStatus.RUNNING) {
            return ToolResultEnvelope.success(
                    "[" + id + "] not cancellable (already " + record.status() + ")");
        }

        manager.cancel(id);
        return ToolResultEnvelope.success("[" + id + "] cancelled");
    }
}
```

- [ ] **Step 4: 运行所有 BackgroundToolsIntegrationTest，确认通过**

```bash
mvn test -Dtest=BackgroundToolsIntegrationTest -q
```

期望：所有测试通过（5 + 4 + 3 + 4 = 16 tests）。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/background/BackgroundCancelTool.java \
        src/test/java/org/example/agent/tool/background/BackgroundToolsIntegrationTest.java
git commit -m "feat(s13): add BackgroundCancelTool"
```

---

## Task 10: QueryEngine 集成

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Test: `src/test/java/org/example/agent/engine/QueryEngineBackgroundTest.java`

- [ ] **Step 1: 写失败测试**

```java
package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.example.agent.core.StopReason;
import org.example.agent.model.ModelClient;
import org.example.agent.model.ModelResponse;
import org.example.agent.tool.ToolRegistry;
import org.example.agent.tool.background.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineBackgroundTest {

    @TempDir Path tempDir;

    @Test
    void background_tools_are_registered_and_available() {
        var registry = new ToolRegistry();
        var engine = new QueryEngine(fakeModel(List.of()), registry);
        var names = registry.definitions().stream()
                .map(d -> d.name()).toList();
        assertTrue(names.contains("background_run"));
        assertTrue(names.contains("background_check"));
        assertTrue(names.contains("background_list"));
        assertTrue(names.contains("background_cancel"));
    }

    @Test
    void drain_notifications_are_injected_before_model_call() throws Exception {
        var callCount = new AtomicInteger();
        var registry = new ToolRegistry();

        // 第一轮：模型调用 background_run
        // 第二轮：模型收到通知后结束
        var model = new ModelClient() {
            @Override
            public ModelResponse call(org.example.agent.model.ModelRequest req) {
                int turn = callCount.incrementAndGet();
                if (turn == 1) {
                    return new ModelResponse(List.of(
                            new ContentBlock.ToolUse("t1", "background_run",
                                    Map.of("command", "echo notify_test"))
                    ), StopReason.TOOL_USE, 10, 5);
                }
                // turn >= 2: verify last user message contains notification if task finished
                return new ModelResponse(
                        List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 10, 5);
            }
        };

        var engine = new QueryEngine(model, registry);
        var result = engine.run(new QueryParams(
                List.of(Message.user("start")), null, null, null, 3));
        assertTrue(result instanceof QueryResult.Success);
        assertTrue(callCount.get() >= 2);
    }

    private ModelClient fakeModel(List<ModelResponse> responses) {
        var idx = new AtomicInteger();
        return req -> {
            int i = idx.getAndIncrement();
            if (i < responses.size()) return responses.get(i);
            return new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN);
        };
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=QueryEngineBackgroundTest#background_tools_are_registered_and_available -q
```

期望：FAIL（background_run 等工具未注册）。

- [ ] **Step 3: 修改 QueryEngine — 添加 import**

在 QueryEngine.java 的 import 区域追加：
```java
import org.example.agent.tool.background.BackgroundCancelTool;
import org.example.agent.tool.background.BackgroundCheckTool;
import org.example.agent.tool.background.BackgroundListTool;
import org.example.agent.tool.background.BackgroundManager;
import org.example.agent.tool.background.BackgroundRunTool;
```

- [ ] **Step 4: 修改 QueryEngine — 注册工具**

在私有构造函数中，`toolRegistry.register(new CompactTool(...));` 之后追加：
```java
        toolRegistry.register(new BackgroundRunTool());
        toolRegistry.register(new BackgroundCheckTool());
        toolRegistry.register(new BackgroundListTool());
        toolRegistry.register(new BackgroundCancelTool());
```

- [ ] **Step 5: 修改 QueryEngine — run() 初始化 BackgroundManager**

在 `run()` 方法中，`ctx = ctx.withTaskManager(new TaskManager(taskManagerPath));` 之后追加：
```java
        var backgroundPath = Paths.get(System.getProperty("user.dir"), ".runtime-tasks");
        var backgroundManager = new BackgroundManager(backgroundPath);
        ctx = ctx.withBackgroundManager(backgroundManager);
```

并将 `currentCtx = ctx;` 移动到这行之后（已在此处则保持不变）。

用 try-finally 包住主循环，确保关闭线程池：
```java
        final var bgManager = backgroundManager;
        try {
            while (true) {
                // ... 现有主循环代码不变 ...
            }
        } finally {
            bgManager.shutdown();
        }
```

- [ ] **Step 6: 修改 QueryEngine — 添加 drainAndInject 方法**

在 `run()` 方法的主循环中，`currentState.replaceMessages(compactor.microCompact(...))` **之前**插入：
```java
            drainAndInject(currentState, currentCtx);
```

在类末尾追加私有方法：
```java
    private void drainAndInject(QueryState state, ToolUseContext ctx) {
        if (ctx.backgroundManager() == null) return;
        var notifications = ctx.backgroundManager().drain();
        if (notifications.isEmpty()) return;
        var text = notifications.stream()
                .map(n -> "[bg:%s] %s - %s\nPreview: %s\nFull output: .runtime-tasks/%s.log"
                        .formatted(n.taskId(), n.status(), n.description(), n.preview(), n.taskId()))
                .collect(java.util.stream.Collectors.joining("\n\n"));
        state.appendMessage(Message.user(text));
    }
```

- [ ] **Step 7: 运行所有测试，确认通过**

```bash
mvn test -q
```

期望：BUILD SUCCESS，全部测试通过。

- [ ] **Step 8: Commit**

```bash
git add src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/engine/QueryEngineBackgroundTest.java
git commit -m "feat(s13): wire BackgroundManager into QueryEngine; add drainAndInject"
```
