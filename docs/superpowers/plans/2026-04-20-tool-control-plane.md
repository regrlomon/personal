# s02a Tool Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `ToolUseContext`, `ToolResultEnvelope`, and `ToolRouter` so the tool layer has a shared execution context, a structured result envelope, and capability-source routing aligned with the s02a control-plane model.

**Architecture:** New value types (`ToolResultEnvelope`, `ToolUseContext`) are added first in isolation. The `Tool` interface is then updated to use them, pulling all implementations and tests along in one atomic commit. `ToolRouter` is added TDD. Finally `QueryEngine` is wired to build a `ToolRouter` internally and create a `ToolUseContext` once per `run()`, and `ToolRegistry.execute()` is deleted.

**Tech Stack:** Java 21, JUnit Jupiter 5, Maven 3

---

## File Map

| Action | Path |
|--------|------|
| Create | `src/main/java/org/example/agent/tool/ToolResultEnvelope.java` |
| Create | `src/main/java/org/example/agent/tool/ToolUseContext.java` |
| Create | `src/main/java/org/example/agent/tool/ToolRouter.java` |
| Create | `src/test/java/org/example/agent/tool/ToolResultEnvelopeTest.java` |
| Create | `src/test/java/org/example/agent/tool/ToolUseContextTest.java` |
| Create | `src/test/java/org/example/agent/tool/ToolRouterTest.java` |
| Modify | `src/main/java/org/example/agent/tool/Tool.java` |
| Modify | `src/main/java/org/example/agent/tool/ToolRegistry.java` |
| Modify | `src/main/java/org/example/agent/tool/BashTool.java` |
| Modify | `src/main/java/org/example/agent/tool/ReadFileTool.java` |
| Modify | `src/main/java/org/example/agent/tool/WriteFileTool.java` |
| Modify | `src/main/java/org/example/agent/tool/EditFileTool.java` |
| Modify | `src/main/java/org/example/agent/engine/QueryEngine.java` |
| Modify | `src/test/java/org/example/agent/tool/BashToolTest.java` |
| Modify | `src/test/java/org/example/agent/tool/FileToolsTest.java` |
| Modify | `src/test/java/org/example/agent/tool/ToolRegistryTest.java` |
| Modify | `src/test/java/org/example/agent/engine/QueryEngineTest.java` |

---

## Task 1: ToolResultEnvelope

**Files:**
- Create: `src/test/java/org/example/agent/tool/ToolResultEnvelopeTest.java`
- Create: `src/main/java/org/example/agent/tool/ToolResultEnvelope.java`

- [ ] **Step 1: Write the failing test**

```java
package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolResultEnvelopeTest {

    @Test
    void success_sets_ok_true_content_and_no_error() {
        var e = ToolResultEnvelope.success("hello");
        assertTrue(e.ok());
        assertEquals("hello", e.content());
        assertFalse(e.isError());
        assertTrue(e.attachments().isEmpty());
    }

    @Test
    void error_sets_ok_false_content_and_is_error() {
        var e = ToolResultEnvelope.error("boom");
        assertFalse(e.ok());
        assertEquals("boom", e.content());
        assertTrue(e.isError());
        assertTrue(e.attachments().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /e/personal/java/personal && mvn test -Dtest=ToolResultEnvelopeTest -q 2>&1 | tail -5
```

Expected: `COMPILATION ERROR` — `ToolResultEnvelope` does not exist yet.

- [ ] **Step 3: Implement ToolResultEnvelope**

```java
package org.example.agent.tool;

import java.util.List;

public record ToolResultEnvelope(
        boolean ok,
        String content,
        boolean isError,
        List<Object> attachments
) {
    public static ToolResultEnvelope success(String content) {
        return new ToolResultEnvelope(true, content, false, List.of());
    }

    public static ToolResultEnvelope error(String message) {
        return new ToolResultEnvelope(false, message, true, List.of());
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /e/personal/java/personal && mvn test -Dtest=ToolResultEnvelopeTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 2 tests passed.

- [ ] **Step 5: Commit**

```bash
cd /e/personal/java/personal && git add src/main/java/org/example/agent/tool/ToolResultEnvelope.java src/test/java/org/example/agent/tool/ToolResultEnvelopeTest.java && git commit -m "feat(tool): add ToolResultEnvelope record with success/error factories"
```

---

## Task 2: ToolUseContext

**Files:**
- Create: `src/test/java/org/example/agent/tool/ToolUseContextTest.java`
- Create: `src/main/java/org/example/agent/tool/ToolUseContext.java`

- [ ] **Step 1: Write the failing test**

```java
package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolUseContextTest {

    @Test
    void defaults_sets_cwd_and_empty_stub_collections() {
        var ctx = ToolUseContext.defaults("/workspace");
        assertEquals("/workspace", ctx.cwd());
        assertTrue(ctx.permissionContext().isEmpty());
        assertTrue(ctx.mcpClients().isEmpty());
        assertTrue(ctx.appState().isEmpty());
        assertTrue(ctx.notifications().isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /e/personal/java/personal && mvn test -Dtest=ToolUseContextTest -q 2>&1 | tail -5
```

Expected: `COMPILATION ERROR` — `ToolUseContext` does not exist yet.

- [ ] **Step 3: Implement ToolUseContext**

```java
package org.example.agent.tool;

import java.util.List;
import java.util.Map;

public class ToolUseContext {

    private final Map<String, Object> permissionContext;
    private final Map<String, Object> mcpClients;
    private final Map<String, Object> appState;
    private final List<String> notifications;
    private final String cwd;

    private ToolUseContext(Map<String, Object> permissionContext,
                           Map<String, Object> mcpClients,
                           Map<String, Object> appState,
                           List<String> notifications,
                           String cwd) {
        this.permissionContext = permissionContext;
        this.mcpClients = mcpClients;
        this.appState = appState;
        this.notifications = notifications;
        this.cwd = cwd;
    }

    public static ToolUseContext defaults(String cwd) {
        return new ToolUseContext(Map.of(), Map.of(), Map.of(), List.of(), cwd);
    }

    public Map<String, Object> permissionContext() { return permissionContext; }
    public Map<String, Object> mcpClients()        { return mcpClients; }
    public Map<String, Object> appState()          { return appState; }
    public List<String>        notifications()     { return notifications; }
    public String              cwd()               { return cwd; }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /e/personal/java/personal && mvn test -Dtest=ToolUseContextTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 1 test passed.

- [ ] **Step 5: Commit**

```bash
cd /e/personal/java/personal && git add src/main/java/org/example/agent/tool/ToolUseContext.java src/test/java/org/example/agent/tool/ToolUseContextTest.java && git commit -m "feat(tool): add ToolUseContext with defaults factory"
```

---

## Task 3: Update Tool interface, implementations, and all affected tests

Changing the `Tool` interface signature is a compile-breaking change. All four implementations and every test that defines an anonymous `Tool` must be updated atomically. `ToolRegistry.execute()` is updated internally to use the new signature (it still exists for now — it is removed in Task 5).

**Files:**
- Modify: `src/main/java/org/example/agent/tool/Tool.java`
- Modify: `src/main/java/org/example/agent/tool/ToolRegistry.java`
- Modify: `src/main/java/org/example/agent/tool/BashTool.java`
- Modify: `src/main/java/org/example/agent/tool/ReadFileTool.java`
- Modify: `src/main/java/org/example/agent/tool/WriteFileTool.java`
- Modify: `src/main/java/org/example/agent/tool/EditFileTool.java`
- Modify: `src/test/java/org/example/agent/tool/BashToolTest.java`
- Modify: `src/test/java/org/example/agent/tool/FileToolsTest.java`
- Modify: `src/test/java/org/example/agent/tool/ToolRegistryTest.java`
- Modify: `src/test/java/org/example/agent/engine/QueryEngineTest.java`

- [ ] **Step 1: Update Tool.java**

Replace the entire file:

```java
package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;
import java.util.Map;

public interface Tool {
    ToolDefinition definition();
    ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx);
}
```

- [ ] **Step 2: Update BashTool.java — replace execute()**

Replace the `execute` method:

```java
@Override
public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
    try {
        var process = new ProcessBuilder("bash", "-c", (String) input.get("command"))
                .redirectErrorStream(true)
                .start();
        var output = new String(process.getInputStream().readAllBytes());
        process.waitFor();
        return ToolResultEnvelope.success(output);
    } catch (IOException | InterruptedException e) {
        Thread.currentThread().interrupt();
        return ToolResultEnvelope.error("Error: " + e.getMessage());
    }
}
```

- [ ] **Step 3: Update ReadFileTool.java — replace execute()**

Replace the `execute` method:

```java
@Override
public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
    try {
        var path = sandbox.resolve((String) input.get("path"));
        var text = Files.readString(path);
        var lines = text.split("\n", -1);
        Object limitObj = input.get("limit");
        if (limitObj != null) {
            int limit = ((Number) limitObj).intValue();
            if (limit < lines.length) {
                text = String.join("\n", java.util.Arrays.copyOf(lines, limit));
            }
        }
        var result = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        return ToolResultEnvelope.success(result);
    } catch (SecurityException e) {
        return ToolResultEnvelope.error("Error: " + e.getMessage());
    } catch (IOException e) {
        return ToolResultEnvelope.error("Error: cannot read file: " + e.getMessage());
    }
}
```

- [ ] **Step 4: Update WriteFileTool.java — replace execute()**

Replace the `execute` method:

```java
@Override
public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
    try {
        var path = sandbox.resolve((String) input.get("path"));
        Files.createDirectories(path.getParent());
        Files.writeString(path, (String) input.get("content"));
        return ToolResultEnvelope.success("OK: wrote " + path.getFileName());
    } catch (SecurityException e) {
        return ToolResultEnvelope.error("Error: " + e.getMessage());
    } catch (IOException e) {
        return ToolResultEnvelope.error("Error: cannot write file: " + e.getMessage());
    }
}
```

- [ ] **Step 5: Update EditFileTool.java — replace execute()**

Replace the `execute` method:

```java
@Override
public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
    try {
        var path = sandbox.resolve((String) input.get("path"));
        var oldText = (String) input.get("old_text");
        var newText = (String) input.get("new_text");
        var content = Files.readString(path);
        if (!content.contains(oldText)) {
            return ToolResultEnvelope.error("Error: old_text not found in " + path.getFileName());
        }
        Files.writeString(path, content.replace(oldText, newText));
        return ToolResultEnvelope.success("OK: edited " + path.getFileName());
    } catch (SecurityException e) {
        return ToolResultEnvelope.error("Error: " + e.getMessage());
    } catch (IOException e) {
        return ToolResultEnvelope.error("Error: cannot edit file: " + e.getMessage());
    }
}
```

- [ ] **Step 6: Update ToolRegistry.java — update execute() body, add get()**

Replace the entire file:

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        tools.put(tool.definition().name(), tool);
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    public Tool get(String name) {
        return tools.get(name);
    }

    public ContentBlock.ToolResult execute(ContentBlock.ToolUse toolUse) {
        var ctx = ToolUseContext.defaults(System.getProperty("user.dir"));
        var tool = tools.get(toolUse.name());
        if (tool == null) {
            return new ContentBlock.ToolResult(toolUse.id(), "Unknown tool: " + toolUse.name());
        }
        var envelope = tool.execute(toolUse.input(), ctx);
        return new ContentBlock.ToolResult(toolUse.id(), envelope.content());
    }
}
```

- [ ] **Step 7: Fix BashToolTest.java**

Replace the entire file:

```java
package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    private final ToolUseContext ctx = ToolUseContext.defaults(".");

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void executes_command_and_returns_stdout() {
        var tool = new BashTool();
        var result = tool.execute(Map.of("command", "echo hello"), ctx);
        assertEquals("hello", result.content().strip());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void captures_stderr_on_failure() {
        var tool = new BashTool();
        var result = tool.execute(Map.of("command", "ls /nonexistent_path_xyz 2>&1"), ctx);
        assertFalse(result.content().isBlank());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void returns_combined_stdout_and_stderr() {
        var tool = new BashTool();
        var result = tool.execute(Map.of("command", "echo out && echo err >&2"), ctx);
        assertTrue(result.content().contains("out"));
    }
}
```

- [ ] **Step 8: Fix FileToolsTest.java**

Replace the entire file:

```java
package org.example.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {

    @TempDir
    Path workdir;

    PathSandbox sandbox;
    ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        sandbox = new PathSandbox(workdir);
        ctx = ToolUseContext.defaults(workdir.toString());
    }

    // --- ReadFileTool ---

    @Test
    void read_returns_file_contents() throws IOException {
        Files.writeString(workdir.resolve("hello.txt"), "line1\nline2\nline3");
        var tool = new ReadFileTool(sandbox);
        var result = tool.execute(Map.of("path", "hello.txt"), ctx);
        assertEquals("line1\nline2\nline3", result.content());
    }

    @Test
    void read_truncates_to_limit() throws IOException {
        Files.writeString(workdir.resolve("big.txt"), "a\nb\nc\nd\ne");
        var tool = new ReadFileTool(sandbox);
        var result = tool.execute(Map.of("path", "big.txt", "limit", 3), ctx);
        assertEquals("a\nb\nc", result.content());
    }

    @Test
    void read_returns_error_on_missing_file() {
        var tool = new ReadFileTool(sandbox);
        var result = tool.execute(Map.of("path", "missing.txt"), ctx);
        assertTrue(result.content().startsWith("Error:"));
    }

    @Test
    void read_rejects_path_outside_workdir() {
        var tool = new ReadFileTool(sandbox);
        var result = tool.execute(Map.of("path", "../../etc/passwd"), ctx);
        assertTrue(result.content().startsWith("Error:"));
    }

    // --- WriteFileTool ---

    @Test
    void write_creates_file_with_content() {
        var tool = new WriteFileTool(sandbox);
        tool.execute(Map.of("path", "out.txt", "content", "hello"), ctx);
        assertDoesNotThrow(() -> {
            var written = Files.readString(workdir.resolve("out.txt"));
            assertEquals("hello", written);
        });
    }

    @Test
    void write_overwrites_existing_file() throws IOException {
        Files.writeString(workdir.resolve("existing.txt"), "old");
        var tool = new WriteFileTool(sandbox);
        tool.execute(Map.of("path", "existing.txt", "content", "new"), ctx);
        assertEquals("new", Files.readString(workdir.resolve("existing.txt")));
    }

    @Test
    void write_rejects_path_outside_workdir() {
        var tool = new WriteFileTool(sandbox);
        var result = tool.execute(Map.of("path", "../../evil.txt", "content", "x"), ctx);
        assertTrue(result.content().startsWith("Error:"));
    }

    // --- EditFileTool ---

    @Test
    void edit_replaces_old_text_with_new_text() throws IOException {
        Files.writeString(workdir.resolve("code.java"), "int x = 1;");
        var tool = new EditFileTool(sandbox);
        tool.execute(Map.of("path", "code.java", "old_text", "int x = 1;", "new_text", "int x = 42;"), ctx);
        assertEquals("int x = 42;", Files.readString(workdir.resolve("code.java")));
    }

    @Test
    void edit_returns_error_when_old_text_not_found() throws IOException {
        Files.writeString(workdir.resolve("code.java"), "int x = 1;");
        var tool = new EditFileTool(sandbox);
        var result = tool.execute(Map.of("path", "code.java", "old_text", "NOT FOUND", "new_text", "x"), ctx);
        assertTrue(result.content().startsWith("Error:"));
    }

    @Test
    void edit_rejects_path_outside_workdir() {
        var tool = new EditFileTool(sandbox);
        var result = tool.execute(Map.of("path", "../../evil.java", "old_text", "a", "new_text", "b"), ctx);
        assertTrue(result.content().startsWith("Error:"));
    }
}
```

- [ ] **Step 9: Fix ToolRegistryTest.java — update anonymous Tool stubs**

Replace `echoTool()` and the anonymous noop `Tool` in `register_multiple_tools_all_appear_in_definitions`:

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private Tool echoTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "Echoes input", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success(String.valueOf(input.get("text")));
            }
        };
    }

    @Test
    void definitions_returns_registered_tool_definitions() {
        var registry = new ToolRegistry();
        registry.register(echoTool());
        assertEquals(1, registry.definitions().size());
        assertEquals("echo", registry.definitions().get(0).name());
    }

    @Test
    void execute_known_tool_returns_tool_result_block() {
        var registry = new ToolRegistry();
        registry.register(echoTool());

        var toolUse = new ContentBlock.ToolUse("call-1", "echo", Map.of("text", "hello"));
        var result = registry.execute(toolUse);

        assertEquals("call-1", result.toolUseId());
        assertEquals("hello", result.content());
    }

    @Test
    void execute_unknown_tool_returns_error_string() {
        var registry = new ToolRegistry();
        var toolUse = new ContentBlock.ToolUse("call-2", "nonexistent", Map.of());

        var result = registry.execute(toolUse);
        assertEquals("call-2", result.toolUseId());
        assertTrue(result.content().contains("nonexistent"));
    }

    @Test
    void register_multiple_tools_all_appear_in_definitions() {
        var registry = new ToolRegistry();
        registry.register(echoTool());
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("noop", "Does nothing", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("ok");
            }
        });
        assertEquals(2, registry.definitions().size());
    }
}
```

- [ ] **Step 10: Fix QueryEngineTest.java — update the greet Tool stub**

In `tool_use_executes_tool_appends_result_and_continues`, replace the anonymous Tool class:

```java
registry.register(new Tool() {
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("greet", "Greet someone", Map.of());
    }
    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        return ToolResultEnvelope.success("Hello, " + input.get("name") + "!");
    }
});
```

Also add the imports at the top of `QueryEngineTest.java`:

```java
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;
```

- [ ] **Step 11: Run all tests**

```bash
cd /e/personal/java/personal && mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` — all tests pass (BashTool tests skipped on Windows is fine).

- [ ] **Step 12: Commit**

```bash
cd /e/personal/java/personal && git add \
  src/main/java/org/example/agent/tool/Tool.java \
  src/main/java/org/example/agent/tool/BashTool.java \
  src/main/java/org/example/agent/tool/ReadFileTool.java \
  src/main/java/org/example/agent/tool/WriteFileTool.java \
  src/main/java/org/example/agent/tool/EditFileTool.java \
  src/main/java/org/example/agent/tool/ToolRegistry.java \
  src/test/java/org/example/agent/tool/BashToolTest.java \
  src/test/java/org/example/agent/tool/FileToolsTest.java \
  src/test/java/org/example/agent/tool/ToolRegistryTest.java \
  src/test/java/org/example/agent/engine/QueryEngineTest.java \
  && git commit -m "refactor(tool): update Tool interface to execute(input, ctx) returning ToolResultEnvelope"
```

---

## Task 4: ToolRouter

**Files:**
- Create: `src/test/java/org/example/agent/tool/ToolRouterTest.java`
- Create: `src/main/java/org/example/agent/tool/ToolRouter.java`

- [ ] **Step 1: Write the failing test**

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRouterTest {

    private ToolRegistry registryWithEcho() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "Echoes input", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success(String.valueOf(input.get("text")));
            }
        });
        return registry;
    }

    private final ToolUseContext ctx = ToolUseContext.defaults(".");

    @Test
    void routes_native_tool_and_returns_tool_result() {
        var router = new ToolRouter(registryWithEcho());
        var toolUse = new ContentBlock.ToolUse("id-1", "echo", Map.of("text", "hello"));
        var result = router.route(toolUse, ctx);
        assertEquals("id-1", result.toolUseId());
        assertEquals("hello", result.content());
    }

    @Test
    void throws_unknown_tool_exception_for_unregistered_tool() {
        var router = new ToolRouter(new ToolRegistry());
        var toolUse = new ContentBlock.ToolUse("id-2", "missing", Map.of());
        assertThrows(UnknownToolException.class, () -> router.route(toolUse, ctx));
    }

    @Test
    void throws_unsupported_operation_for_mcp_prefix() {
        var router = new ToolRouter(new ToolRegistry());
        var toolUse = new ContentBlock.ToolUse("id-3", "mcp__postgres__query", Map.of());
        assertThrows(UnsupportedOperationException.class, () -> router.route(toolUse, ctx));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /e/personal/java/personal && mvn test -Dtest=ToolRouterTest -q 2>&1 | tail -5
```

Expected: `COMPILATION ERROR` — `ToolRouter` does not exist yet.

- [ ] **Step 3: Implement ToolRouter**

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;

public class ToolRouter {

    private final ToolRegistry registry;

    public ToolRouter(ToolRegistry registry) {
        this.registry = registry;
    }

    public ContentBlock.ToolResult route(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        if (toolUse.name().startsWith("mcp__")) return routeMcp(toolUse, ctx);
        return routeNative(toolUse, ctx);
    }

    private ContentBlock.ToolResult routeNative(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        var tool = registry.get(toolUse.name());
        if (tool == null) throw new UnknownToolException(toolUse.name());
        var envelope = tool.execute(toolUse.input(), ctx);
        return new ContentBlock.ToolResult(toolUse.id(), envelope.content());
    }

    private ContentBlock.ToolResult routeMcp(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        throw new UnsupportedOperationException("MCP tools not implemented (s19)");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /e/personal/java/personal && mvn test -Dtest=ToolRouterTest -q 2>&1 | tail -5
```

Expected: `BUILD SUCCESS`, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
cd /e/personal/java/personal && git add src/main/java/org/example/agent/tool/ToolRouter.java src/test/java/org/example/agent/tool/ToolRouterTest.java && git commit -m "feat(tool): add ToolRouter with native dispatch and mcp__ stub"
```

---

## Task 5: Wire QueryEngine + clean up ToolRegistry

`QueryEngine` builds a `ToolRouter` from the injected `ToolRegistry`, creates `ToolUseContext` once per `run()`, and routes tool calls through `ToolRouter`. `ToolRegistry.execute()` is then deleted, and `ToolRegistryTest` is updated to match.

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Modify: `src/main/java/org/example/agent/tool/ToolRegistry.java`
- Modify: `src/test/java/org/example/agent/tool/ToolRegistryTest.java`

- [ ] **Step 1: Update QueryEngine.java**

Replace the entire file:

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelClient;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.tool.ToolRegistry;
import org.example.agent.tool.ToolRouter;
import org.example.agent.tool.ToolUseContext;

import java.util.List;

public class QueryEngine {

    private static final String CONTINUE_PROMPT = "Please continue.";

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolRouter toolRouter;

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.toolRouter = new ToolRouter(toolRegistry);
    }

    public QueryResult run(QueryParams params) {
        var state = QueryState.from(params);
        var ctx = ToolUseContext.defaults(System.getProperty("user.dir"));
        while (true) {
            var request = buildRequest(state, params);
            var response = modelClient.call(request);

            var transition = decide(state, response, ctx);
            if (transition == null) {
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                return new QueryResult.Success(state.messages(), state.turnCount());
            }
            advance(state, transition, response);
        }
    }

    private TransitionReason decide(QueryState state, ModelResponse response, ToolUseContext ctx) {
        return switch (response.stopReason()) {
            case END_TURN -> null;
            case TOOL_USE -> new TransitionReason.ToolResultContinuation(collectResults(response, ctx));
            case MAX_TOKENS -> new TransitionReason.MaxTokensRecovery(state.continuationCount() + 1);
            case STOP_SEQUENCE -> null;
        };
    }

    private void advance(QueryState state, TransitionReason t, ModelResponse response) {
        switch (t) {
            case TransitionReason.ToolResultContinuation c -> {
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                state.appendMessage(buildToolResultMessage(c.results()));
                state.setLastTransition(t);
                state.incrementTurn();
            }
            case TransitionReason.MaxTokensRecovery m -> {
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                state.appendMessage(Message.user(CONTINUE_PROMPT));
                state.incrementContinuation();
                state.setLastTransition(t);
                state.incrementTurn();
            }
            case TransitionReason.CompactRetry c -> { /* s06 extension */ }
            case TransitionReason.TransportRetry r -> { /* s11 extension */ }
            case TransitionReason.StopHookContinuation h -> { /* s08 extension */ }
            case TransitionReason.BudgetContinuation b -> { /* budget extension */ }
        }
    }

    private ModelRequest buildRequest(QueryState state, QueryParams params) {
        Integer maxTokens = state.maxOutputTokensOverride()
                .orElse(params.maxOutputTokensOverride());
        return new ModelRequest(
                MessageNormalizer.normalize(state.messages()),
                params.systemPrompt(),
                toolRegistry.definitions(),
                maxTokens
        );
    }

    private List<ContentBlock.ToolResult> collectResults(ModelResponse response, ToolUseContext ctx) {
        return response.content().stream()
                .filter(b -> b instanceof ContentBlock.ToolUse)
                .map(b -> toolRouter.route((ContentBlock.ToolUse) b, ctx))
                .toList();
    }

    private Message buildToolResultMessage(List<ContentBlock.ToolResult> results) {
        return new Message(Role.USER, List.copyOf(results));
    }
}
```

- [ ] **Step 2: Remove ToolRegistry.execute()**

Replace the entire `ToolRegistry.java`:

```java
package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        tools.put(tool.definition().name(), tool);
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    public Tool get(String name) {
        return tools.get(name);
    }
}
```

- [ ] **Step 3: Update ToolRegistryTest.java — remove execute tests, add get tests**

Replace the entire file:

```java
package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private Tool echoTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "Echoes input", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success(String.valueOf(input.get("text")));
            }
        };
    }

    @Test
    void definitions_returns_registered_tool_definitions() {
        var registry = new ToolRegistry();
        registry.register(echoTool());
        assertEquals(1, registry.definitions().size());
        assertEquals("echo", registry.definitions().get(0).name());
    }

    @Test
    void get_returns_registered_tool_by_name() {
        var registry = new ToolRegistry();
        var tool = echoTool();
        registry.register(tool);
        assertSame(tool, registry.get("echo"));
    }

    @Test
    void get_returns_null_for_unknown_tool() {
        var registry = new ToolRegistry();
        assertNull(registry.get("nonexistent"));
    }

    @Test
    void register_multiple_tools_all_appear_in_definitions() {
        var registry = new ToolRegistry();
        registry.register(echoTool());
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("noop", "Does nothing", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("ok");
            }
        });
        assertEquals(2, registry.definitions().size());
    }
}
```

- [ ] **Step 4: Run all tests**

```bash
cd /e/personal/java/personal && mvn test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS` — all tests pass.

- [ ] **Step 5: Commit**

```bash
cd /e/personal/java/personal && git add src/main/java/org/example/agent/engine/QueryEngine.java src/main/java/org/example/agent/tool/ToolRegistry.java src/test/java/org/example/agent/tool/ToolRegistryTest.java && git commit -m "refactor(engine): wire ToolRouter into QueryEngine, remove ToolRegistry.execute()"
```
