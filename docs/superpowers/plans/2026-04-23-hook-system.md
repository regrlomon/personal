# Hook System (s08) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不修改主循环逻辑的前提下，引入 Hook 机制，支持通过 `src/main/resources/hooks.json` 配置 shell 命令脚本，在 SessionStart / PreToolUse / PostToolUse 三个时机插入额外行为。

**Architecture:** 新增 `hook` 包（HookEventName / HookEvent / HookResult / HookRunner / HookConfig / ShellHookRunner）。HookRunner 通过 ToolUseContext 注入（与 s07 PermissionChecker 对称）。ToolExecutionRuntime 在每次工具执行前后调用 hook，injection messages 通过 ExecutionResult 传回 QueryEngine。

**Tech Stack:** Java 21, JUnit 5, ProcessBuilder（无额外依赖）

---

## 文件地图

| 操作 | 路径 |
|---|---|
| 新建 | `src/main/java/org/example/agent/hook/HookEventName.java` |
| 新建 | `src/main/java/org/example/agent/hook/HookEvent.java` |
| 新建 | `src/main/java/org/example/agent/hook/HookResult.java` |
| 新建 | `src/main/java/org/example/agent/hook/HookRunner.java` |
| 新建 | `src/main/java/org/example/agent/hook/HookConfig.java` |
| 新建 | `src/main/java/org/example/agent/hook/ShellHookRunner.java` |
| 新建 | `src/main/resources/hooks.json` |
| 修改 | `src/main/java/org/example/agent/tool/ToolUseContext.java` |
| 修改 | `src/main/java/org/example/agent/tool/ExecutionResult.java` |
| 修改 | `src/main/java/org/example/agent/tool/ToolExecutionRuntime.java` |
| 修改 | `src/main/java/org/example/agent/engine/QueryEngine.java` |
| 新建测试 | `src/test/java/org/example/agent/hook/HookResultTest.java` |
| 新建测试 | `src/test/java/org/example/agent/hook/HookConfigTest.java` |
| 新建测试 | `src/test/java/org/example/agent/hook/ShellHookRunnerTest.java` |
| 新建测试 | `src/test/java/org/example/agent/tool/ToolExecutionRuntimeHookTest.java` |
| 新建测试 | `src/test/java/org/example/agent/engine/QueryEngineHookTest.java` |
| 新建测试资源 | `src/test/resources/hooks.json` |

---

## Task 1: 核心数据类型 HookEventName / HookEvent / HookResult

**Files:**
- Create: `src/main/java/org/example/agent/hook/HookEventName.java`
- Create: `src/main/java/org/example/agent/hook/HookEvent.java`
- Create: `src/main/java/org/example/agent/hook/HookResult.java`
- Create: `src/test/java/org/example/agent/hook/HookResultTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/org/example/agent/hook/HookResultTest.java
package org.example.agent.hook;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HookResultTest {

    @Test
    void ok_has_exit_code_0_and_empty_message() {
        var r = HookResult.ok();
        assertEquals(0, r.exitCode());
        assertEquals("", r.message());
    }

    @Test
    void block_has_exit_code_1() {
        var r = HookResult.block("not allowed");
        assertEquals(1, r.exitCode());
        assertEquals("not allowed", r.message());
    }

    @Test
    void inject_has_exit_code_2() {
        var r = HookResult.inject("extra context");
        assertEquals(2, r.exitCode());
        assertEquals("extra context", r.message());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=HookResultTest -q
```

预期：编译错误，`HookResult` 不存在。

- [ ] **Step 3: 实现三个数据类型**

```java
// src/main/java/org/example/agent/hook/HookEventName.java
package org.example.agent.hook;

public enum HookEventName {
    SESSION_START, PRE_TOOL_USE, POST_TOOL_USE
}
```

```java
// src/main/java/org/example/agent/hook/HookEvent.java
package org.example.agent.hook;

import java.util.Map;

public record HookEvent(HookEventName name, Map<String, Object> payload) {}
```

```java
// src/main/java/org/example/agent/hook/HookResult.java
package org.example.agent.hook;

public record HookResult(int exitCode, String message) {
    public static HookResult ok()                   { return new HookResult(0, ""); }
    public static HookResult block(String message)  { return new HookResult(1, message); }
    public static HookResult inject(String message) { return new HookResult(2, message); }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn test -Dtest=HookResultTest -q
```

预期：BUILD SUCCESS，3 tests passed。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/hook/ src/test/java/org/example/agent/hook/HookResultTest.java
git commit -m "feat(hook): add HookEventName, HookEvent, HookResult"
```

---

## Task 2: HookRunner 接口 + HookConfig（加载 hooks.json）

**Files:**
- Create: `src/main/java/org/example/agent/hook/HookRunner.java`
- Create: `src/main/java/org/example/agent/hook/HookConfig.java`
- Create: `src/test/java/org/example/agent/hook/HookConfigTest.java`
- Create: `src/test/resources/hooks.json`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/org/example/agent/hook/HookConfigTest.java
package org.example.agent.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HookConfigTest {

    @Test
    void load_from_file_parses_commands(@TempDir Path dir) throws Exception {
        var json = """
                {
                  "SessionStart": ["./scripts/start.sh"],
                  "PreToolUse": ["./guard.sh", "./log.sh"],
                  "PostToolUse": ["./post.sh"]
                }
                """;
        var file = dir.resolve("hooks.json");
        Files.writeString(file, json);

        var config = HookConfig.load(file);

        assertEquals(List.of("./scripts/start.sh"), config.commandsFor(HookEventName.SESSION_START));
        assertEquals(List.of("./guard.sh", "./log.sh"), config.commandsFor(HookEventName.PRE_TOOL_USE));
        assertEquals(List.of("./post.sh"), config.commandsFor(HookEventName.POST_TOOL_USE));
    }

    @Test
    void unknown_event_names_are_ignored(@TempDir Path dir) throws Exception {
        var json = """
                {
                  "UnknownEvent": ["./whatever.sh"],
                  "PreToolUse": ["./guard.sh"]
                }
                """;
        var file = dir.resolve("hooks.json");
        Files.writeString(file, json);

        var config = HookConfig.load(file);

        assertEquals(List.of("./guard.sh"), config.commandsFor(HookEventName.PRE_TOOL_USE));
        assertTrue(config.commandsFor(HookEventName.SESSION_START).isEmpty());
    }

    @Test
    void empty_returns_no_commands() {
        var config = HookConfig.empty();
        assertTrue(config.commandsFor(HookEventName.PRE_TOOL_USE).isEmpty());
        assertTrue(config.commandsFor(HookEventName.SESSION_START).isEmpty());
        assertTrue(config.commandsFor(HookEventName.POST_TOOL_USE).isEmpty());
    }

    @Test
    void load_from_classpath_finds_test_hooks_json() {
        // requires src/test/resources/hooks.json to exist
        var config = HookConfig.loadFromClasspath();
        assertNotNull(config);
        // test resource has PreToolUse configured
        assertFalse(config.commandsFor(HookEventName.PRE_TOOL_USE).isEmpty());
    }
}
```

- [ ] **Step 2: 建立测试资源文件**

```json
// src/test/resources/hooks.json
{
  "PreToolUse": ["exit 0"]
}
```

- [ ] **Step 3: 运行测试，确认失败**

```bash
mvn test -Dtest=HookConfigTest -q
```

预期：编译错误，`HookConfig` 不存在。

- [ ] **Step 4: 实现 HookRunner 和 HookConfig**

```java
// src/main/java/org/example/agent/hook/HookRunner.java
package org.example.agent.hook;

public interface HookRunner {
    HookResult run(HookEvent event);
}
```

```java
// src/main/java/org/example/agent/hook/HookConfig.java
package org.example.agent.hook;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class HookConfig {

    private final Map<HookEventName, List<String>> commands;

    private HookConfig(Map<HookEventName, List<String>> commands) {
        this.commands = commands;
    }

    public List<String> commandsFor(HookEventName event) {
        return commands.getOrDefault(event, List.of());
    }

    public static HookConfig load(Path path) throws Exception {
        return parse(Files.readString(path));
    }

    public static HookConfig loadFromClasspath() {
        try (InputStream is = HookConfig.class.getClassLoader().getResourceAsStream("hooks.json")) {
            if (is == null) return empty();
            return parse(new String(is.readAllBytes()));
        } catch (Exception e) {
            return empty();
        }
    }

    public static HookConfig empty() {
        return new HookConfig(Map.of());
    }

    // package-private, for tests
    static HookConfig fromMap(Map<HookEventName, List<String>> map) {
        return new HookConfig(Collections.unmodifiableMap(new EnumMap<>(map)));
    }

    private static HookConfig parse(String json) {
        Map<HookEventName, List<String>> result = new EnumMap<>(HookEventName.class);
        var entryPat = Pattern.compile("\"(\\w+)\"\\s*:\\s*\\[([^\\]]*)]");
        var strPat   = Pattern.compile("\"([^\"]*)\"");
        var m = entryPat.matcher(json);
        while (m.find()) {
            var event = parseEventName(m.group(1));
            if (event == null) continue;
            var cmds = new ArrayList<String>();
            var sm = strPat.matcher(m.group(2));
            while (sm.find()) cmds.add(sm.group(1));
            result.put(event, List.copyOf(cmds));
        }
        return new HookConfig(Collections.unmodifiableMap(result));
    }

    private static HookEventName parseEventName(String key) {
        return switch (key) {
            case "SessionStart" -> HookEventName.SESSION_START;
            case "PreToolUse"   -> HookEventName.PRE_TOOL_USE;
            case "PostToolUse"  -> HookEventName.POST_TOOL_USE;
            default             -> null;
        };
    }
}
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
mvn test -Dtest=HookConfigTest -q
```

预期：BUILD SUCCESS，4 tests passed。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/hook/HookRunner.java \
        src/main/java/org/example/agent/hook/HookConfig.java \
        src/test/java/org/example/agent/hook/HookConfigTest.java \
        src/test/resources/hooks.json
git commit -m "feat(hook): add HookRunner interface and HookConfig"
```

---

## Task 3: ShellHookRunner（fork 子进程执行 shell 命令）

**Files:**
- Create: `src/main/java/org/example/agent/hook/ShellHookRunner.java`
- Create: `src/test/java/org/example/agent/hook/ShellHookRunnerTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/org/example/agent/hook/ShellHookRunnerTest.java
package org.example.agent.hook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShellHookRunnerTest {

    private static ShellHookRunner runnerWith(HookEventName event, List<String> cmds) {
        return new ShellHookRunner(HookConfig.fromMap(Map.of(event, cmds)), 5);
    }

    @Test
    void exit_0_returns_ok() {
        var runner = runnerWith(HookEventName.PRE_TOOL_USE, List.of("exit 0"));
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(0, result.exitCode());
    }

    @Test
    void exit_1_returns_block_with_stdout_as_message() {
        var runner = runnerWith(HookEventName.PRE_TOOL_USE,
                List.of("echo 'not allowed'; exit 1"));
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(1, result.exitCode());
        assertEquals("not allowed", result.message());
    }

    @Test
    void exit_2_returns_inject_with_stdout_as_message() {
        var runner = runnerWith(HookEventName.POST_TOOL_USE,
                List.of("echo 'audit logged'; exit 2"));
        var result = runner.run(new HookEvent(HookEventName.POST_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(2, result.exitCode());
        assertEquals("audit logged", result.message());
    }

    @Test
    void first_blocking_command_short_circuits() {
        // cmd1 blocks, cmd2 should never run
        var runner = runnerWith(HookEventName.PRE_TOOL_USE,
                List.of("echo 'blocked'; exit 1", "echo 'should not run'; exit 0"));
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(1, result.exitCode());
        assertEquals("blocked", result.message());
    }

    @Test
    void multiple_ok_commands_all_run_and_return_ok() {
        var runner = runnerWith(HookEventName.PRE_TOOL_USE,
                List.of("exit 0", "exit 0"));
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(0, result.exitCode());
    }

    @Test
    void timeout_returns_ok_and_does_not_hang() {
        // sleep longer than 1s timeout
        var runner = runnerWith(HookEventName.PRE_TOOL_USE, List.of("sleep 5"));
        var runner1s = new ShellHookRunner(
                HookConfig.fromMap(Map.of(HookEventName.PRE_TOOL_USE, List.of("sleep 5"))), 1);
        var result = runner1s.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(0, result.exitCode(), "timed-out hook should return ok");
    }

    @Test
    void no_commands_for_event_returns_ok() {
        var runner = new ShellHookRunner(HookConfig.empty(), 5);
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of()));
        assertEquals(0, result.exitCode());
    }

    @Test
    void payload_is_sent_as_json_to_stdin() {
        // hook reads tool_name from stdin JSON and echoes it; exit 2 to inject
        var runner = runnerWith(HookEventName.PRE_TOOL_USE,
                List.of("read -r line; echo \"got:$line\"; exit 2"));
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE,
                Map.of("tool_name", "bash")));
        assertEquals(2, result.exitCode());
        assertTrue(result.message().startsWith("got:"), "Expected stdin JSON in output, got: " + result.message());
        assertTrue(result.message().contains("tool_name"), "Expected tool_name in JSON payload");
    }

    @Test
    void toJson_serializes_string_values() {
        var json = ShellHookRunner.toJson(Map.of("key", "value with \"quotes\""));
        assertEquals("{\"key\":\"value with \\\"quotes\\\"\"}", json);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=ShellHookRunnerTest -q
```

预期：编译错误，`ShellHookRunner` 不存在。

- [ ] **Step 3: 实现 ShellHookRunner**

```java
// src/main/java/org/example/agent/hook/ShellHookRunner.java
package org.example.agent.hook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ShellHookRunner implements HookRunner {

    private final HookConfig config;
    private final int timeoutSeconds;

    public ShellHookRunner(HookConfig config) {
        this(config, 10);
    }

    ShellHookRunner(HookConfig config, int timeoutSeconds) {
        this.config = config;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public HookResult run(HookEvent event) {
        List<String> commands = config.commandsFor(event.name());
        if (commands.isEmpty()) return HookResult.ok();
        String payloadJson = toJson(event.payload());
        for (String command : commands) {
            var result = runCommand(command, payloadJson);
            if (result.exitCode() == 1 || result.exitCode() == 2) return result;
        }
        return HookResult.ok();
    }

    private HookResult runCommand(String command, String stdinJson) {
        try {
            var process = new ProcessBuilder("bash", "-c", command).start();

            // Read stdout in background before writing stdin (avoids pipe deadlock)
            var stdoutFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });

            // Write payload JSON to stdin, then close
            try (var out = process.getOutputStream()) {
                out.write(stdinJson.getBytes(StandardCharsets.UTF_8));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("[HookRunner] timed out: " + command);
                return HookResult.ok();
            }

            String stdout = stdoutFuture.get(2, TimeUnit.SECONDS).strip();
            return switch (process.exitValue()) {
                case 1  -> HookResult.block(stdout);
                case 2  -> HookResult.inject(stdout);
                default -> HookResult.ok();
            };
        } catch (Exception e) {
            System.err.println("[HookRunner] hook error: " + e.getMessage());
            return HookResult.ok();
        }
    }

    // package-private for test
    static String toJson(Map<String, Object> map) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(valueToJson(e.getValue()));
        }
        return sb.append("}").toString();
    }

    @SuppressWarnings("unchecked")
    private static String valueToJson(Object v) {
        if (v == null) return "null";
        if (v instanceof String s)
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (v instanceof Map<?, ?> m) return toJson((Map<String, Object>) m);
        return String.valueOf(v);  // Number, Boolean
    }
}
```

- [ ] **Step 4: 运行测试，确认通过**

```bash
mvn test -Dtest=ShellHookRunnerTest -q
```

预期：BUILD SUCCESS，所有测试通过。（`timeout` 测试约需 1 秒。）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/hook/ShellHookRunner.java \
        src/test/java/org/example/agent/hook/ShellHookRunnerTest.java
git commit -m "feat(hook): add ShellHookRunner with process fork and JSON stdin"
```

---

## Task 4: ToolUseContext 增加 hookRunner 字段

**Files:**
- Modify: `src/main/java/org/example/agent/tool/ToolUseContext.java`

- [ ] **Step 1: 在 ToolUseContext 中添加字段、accessor 和工厂方法**

在 `src/main/java/org/example/agent/tool/ToolUseContext.java` 中做以下修改：

**添加 import：**
```java
import org.example.agent.hook.HookRunner;
```

**在字段声明区追加（在 `userConfirmation` 字段之后）：**
```java
private final HookRunner hookRunner;
```

**更新私有构造函数签名，追加参数：**
```java
private ToolUseContext(Map<String, Object> permissionContext,
                       Map<String, Object> mcpClients,
                       Map<String, Object> appState,
                       List<String> notifications,
                       String cwd,
                       PlanningState planningState,
                       PermissionChecker permissionChecker,
                       UserConfirmation userConfirmation,
                       HookRunner hookRunner) {
    this.permissionContext = permissionContext;
    this.mcpClients = mcpClients;
    this.appState = appState;
    this.notifications = notifications;
    this.cwd = cwd;
    this.planningState = planningState;
    this.permissionChecker = permissionChecker;
    this.userConfirmation = userConfirmation;
    this.hookRunner = hookRunner;
}
```

**更新 `defaults()` 静态工厂，追加 `null`：**
```java
public static ToolUseContext defaults(String cwd) {
    Objects.requireNonNull(cwd, "cwd must not be null");
    return new ToolUseContext(Map.of(), Map.of(), Map.of(), List.of(), cwd,
            new PlanningState(), null, null, null);
}
```

**更新 `withNotifications()` 方法，透传 hookRunner：**
```java
public ToolUseContext withNotifications(List<String> notifications) {
    Objects.requireNonNull(notifications, "notifications must not be null");
    return new ToolUseContext(permissionContext, mcpClients, appState,
            List.copyOf(notifications), cwd, planningState, permissionChecker, userConfirmation, hookRunner);
}
```

**更新 `withPermissions()` 方法，透传 hookRunner：**
```java
public ToolUseContext withPermissions(PermissionChecker checker, UserConfirmation confirmation) {
    Objects.requireNonNull(checker, "checker must not be null");
    Objects.requireNonNull(confirmation, "confirmation must not be null");
    return new ToolUseContext(permissionContext, mcpClients, appState,
            notifications, cwd, planningState, checker, confirmation, hookRunner);
}
```

**追加新方法（在 `withPermissions` 之后）：**
```java
public ToolUseContext withHookRunner(HookRunner hookRunner) {
    Objects.requireNonNull(hookRunner, "hookRunner must not be null");
    return new ToolUseContext(permissionContext, mcpClients, appState,
            notifications, cwd, planningState, permissionChecker, userConfirmation, hookRunner);
}

public HookRunner hookRunner() { return hookRunner; }
```

- [ ] **Step 2: 运行全部测试，确认无回归**

```bash
mvn test -q
```

预期：BUILD SUCCESS。（`ToolUseContext` 变化只增不减，现有测试全部通过。）

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/agent/tool/ToolUseContext.java
git commit -m "feat(hook): add hookRunner field to ToolUseContext"
```

---

## Task 5: ExecutionResult + ToolExecutionRuntime hook 集成

**Files:**
- Modify: `src/main/java/org/example/agent/tool/ExecutionResult.java`
- Modify: `src/main/java/org/example/agent/tool/ToolExecutionRuntime.java`
- Create: `src/test/java/org/example/agent/tool/ToolExecutionRuntimeHookTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/org/example/agent/tool/ToolExecutionRuntimeHookTest.java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;
import org.example.agent.hook.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionRuntimeHookTest {

    private static final ToolUseContext BASE_CTX = ToolUseContext.defaults(".");

    private static Tool echoTool(String name, String output) {
        return new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition(name, "", Map.of());
            }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success(output);
            }
        };
    }

    private static ToolExecutionRuntime runtimeWithTool(Tool tool) {
        var registry = new ToolRegistry();
        registry.register(tool);
        return new ToolExecutionRuntime(new ToolRouter(registry), ForkJoinPool.commonPool());
    }

    @Test
    void pre_tool_exit_1_blocks_execution_and_returns_error() {
        HookRunner blockAll = event -> HookResult.block("blocked by policy");
        var ctx = BASE_CTX.withHookRunner(blockAll);
        var runtime = runtimeWithTool(echoTool("bash", "should not run"));

        var result = runtime.execute(
                List.of(new ContentBlock.ToolUse("id1", "bash", Map.of())), ctx);

        assertEquals(1, result.toolResults().size());
        assertTrue(result.toolResults().get(0).content().contains("blocked by policy"),
                "Expected block message in result, got: " + result.toolResults().get(0).content());
        assertTrue(result.injectionMessages().isEmpty());
    }

    @Test
    void pre_tool_exit_2_runs_tool_and_adds_injection_message() {
        HookRunner injectPre = event -> {
            if (event.name() == HookEventName.PRE_TOOL_USE) return HookResult.inject("pre-context");
            return HookResult.ok();
        };
        var ctx = BASE_CTX.withHookRunner(injectPre);
        var runtime = runtimeWithTool(echoTool("bash", "tool output"));

        var result = runtime.execute(
                List.of(new ContentBlock.ToolUse("id1", "bash", Map.of())), ctx);

        assertEquals("tool output", result.toolResults().get(0).content());
        assertTrue(result.injectionMessages().contains("pre-context"),
                "Expected pre-context in injections, got: " + result.injectionMessages());
    }

    @Test
    void post_tool_exit_2_adds_injection_message_after_tool_runs() {
        HookRunner injectPost = event -> {
            if (event.name() == HookEventName.POST_TOOL_USE) return HookResult.inject("audit: tool ran");
            return HookResult.ok();
        };
        var ctx = BASE_CTX.withHookRunner(injectPost);
        var runtime = runtimeWithTool(echoTool("bash", "tool output"));

        var result = runtime.execute(
                List.of(new ContentBlock.ToolUse("id1", "bash", Map.of())), ctx);

        assertEquals("tool output", result.toolResults().get(0).content());
        assertTrue(result.injectionMessages().contains("audit: tool ran"),
                "Expected audit message in injections, got: " + result.injectionMessages());
    }

    @Test
    void no_hook_runner_produces_empty_injection_messages() {
        var runtime = runtimeWithTool(echoTool("bash", "output"));

        var result = runtime.execute(
                List.of(new ContentBlock.ToolUse("id1", "bash", Map.of())), BASE_CTX);

        assertTrue(result.injectionMessages().isEmpty());
    }

    @Test
    void both_pre_and_post_injections_collected() {
        HookRunner both = event -> switch (event.name()) {
            case PRE_TOOL_USE  -> HookResult.inject("pre-msg");
            case POST_TOOL_USE -> HookResult.inject("post-msg");
            default            -> HookResult.ok();
        };
        var ctx = BASE_CTX.withHookRunner(both);
        var runtime = runtimeWithTool(echoTool("bash", "done"));

        var result = runtime.execute(
                List.of(new ContentBlock.ToolUse("id1", "bash", Map.of())), ctx);

        assertEquals(List.of("pre-msg", "post-msg"), result.injectionMessages());
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=ToolExecutionRuntimeHookTest -q
```

预期：编译错误，`result.injectionMessages()` 不存在。

- [ ] **Step 3: 更新 ExecutionResult，增加 injectionMessages 字段**

将 `src/main/java/org/example/agent/tool/ExecutionResult.java` 全文替换为：

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import java.util.List;

public record ExecutionResult(
        List<ContentBlock.ToolResult> toolResults,
        ToolUseContext updatedContext,
        List<String> injectionMessages
) {}
```

- [ ] **Step 4: 更新 ToolExecutionRuntime，引入 RouteResult 并接入 hook**

将 `src/main/java/org/example/agent/tool/ToolExecutionRuntime.java` 全文替换为：

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.hook.HookEvent;
import org.example.agent.hook.HookEventName;
import org.example.agent.hook.HookResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class ToolExecutionRuntime {

    private final ToolRouter router;
    private final ExecutorService executor;

    public ToolExecutionRuntime(ToolRouter router, ExecutorService executor) {
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    public ExecutionResult execute(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        Objects.requireNonNull(toolUses, "toolUses must not be null");
        var batches = partition(toolUses);
        var allResults = new ArrayList<ContentBlock.ToolResult>();
        var allInjections = new ArrayList<String>();
        var currentCtx = ctx;

        for (var batch : batches) {
            var routeResults = executeBatch(batch, currentCtx);
            for (int i = 0; i < routeResults.size(); i++) {
                var rr = routeResults.get(i);
                var toolUse = batch.toolUses().get(i);
                allResults.add(new ContentBlock.ToolResult(toolUse.id(), rr.envelope().content()));
                allInjections.addAll(rr.injectionMessages());
                if (rr.envelope().contextModifier().isPresent()) {
                    currentCtx = rr.envelope().contextModifier().get().apply(currentCtx);
                }
            }
        }
        return new ExecutionResult(List.copyOf(allResults), currentCtx, List.copyOf(allInjections));
    }

    private List<ToolExecutionBatch> partition(List<ContentBlock.ToolUse> toolUses) {
        var batches = new ArrayList<ToolExecutionBatch>();
        var safeBatch = new ArrayList<ContentBlock.ToolUse>();

        for (var toolUse : toolUses) {
            if (router.isConcurrencySafe(toolUse.name())) {
                safeBatch.add(toolUse);
            } else {
                if (!safeBatch.isEmpty()) {
                    batches.add(new ToolExecutionBatch(List.copyOf(safeBatch), true));
                    safeBatch.clear();
                }
                batches.add(new ToolExecutionBatch(List.of(toolUse), false));
            }
        }
        if (!safeBatch.isEmpty()) {
            batches.add(new ToolExecutionBatch(List.copyOf(safeBatch), true));
        }
        return batches;
    }

    private List<RouteResult> executeBatch(ToolExecutionBatch batch, ToolUseContext ctx) {
        if (batch.concurrencySafe()) return executeConcurrently(batch.toolUses(), ctx);
        return executeSerially(batch.toolUses(), ctx);
    }

    private List<RouteResult> executeConcurrently(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        var futures = toolUses.stream()
                .map(tu -> CompletableFuture.supplyAsync(() -> routeSafely(tu, ctx), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private List<RouteResult> executeSerially(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        return toolUses.stream().map(tu -> routeSafely(tu, ctx)).toList();
    }

    private RouteResult routeSafely(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        var hookRunner = ctx.hookRunner();
        var injections = new ArrayList<String>();

        // PreToolUse hook
        if (hookRunner != null) {
            var pre = hookRunner.run(new HookEvent(HookEventName.PRE_TOOL_USE,
                    java.util.Map.of("tool_name", toolUse.name(), "input", toolUse.input())));
            if (pre.exitCode() == 1) {
                return new RouteResult(ToolResultEnvelope.error(pre.message()), List.of());
            }
            if (pre.exitCode() == 2) injections.add(pre.message());
        }

        // Execute tool
        ToolResultEnvelope envelope;
        try {
            envelope = router.routeToEnvelope(toolUse, ctx);
        } catch (UnknownToolException e) {
            envelope = ToolResultEnvelope.error(e.getMessage());
        } catch (UnsupportedOperationException e) {
            envelope = ToolResultEnvelope.error("MCP tools not yet supported");
        }

        // PostToolUse hook
        if (hookRunner != null) {
            var post = hookRunner.run(new HookEvent(HookEventName.POST_TOOL_USE,
                    java.util.Map.of("tool_name", toolUse.name(), "input", toolUse.input(),
                            "output", envelope.content())));
            if (post.exitCode() == 2) injections.add(post.message());
        }

        return new RouteResult(envelope, List.copyOf(injections));
    }

    private record RouteResult(ToolResultEnvelope envelope, List<String> injectionMessages) {}
}
```

- [ ] **Step 5: 运行新测试，确认通过**

```bash
mvn test -Dtest=ToolExecutionRuntimeHookTest -q
```

预期：BUILD SUCCESS，5 tests passed。

- [ ] **Step 6: 运行全部测试，确认无回归**

```bash
mvn test -q
```

预期：BUILD SUCCESS。（现有 `ToolExecutionRuntimeTest` 里的 `result.toolResults()` 和 `result.updatedContext()` 仍可正常访问。）

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/agent/tool/ExecutionResult.java \
        src/main/java/org/example/agent/tool/ToolExecutionRuntime.java \
        src/test/java/org/example/agent/tool/ToolExecutionRuntimeHookTest.java
git commit -m "feat(hook): integrate Pre/PostToolUse hooks in ToolExecutionRuntime"
```

---

## Task 6: QueryEngine 接入 SessionStart hook + 处理注入消息

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Create: `src/test/java/org/example/agent/engine/QueryEngineHookTest.java`

- [ ] **Step 1: 写失败测试**

```java
// src/test/java/org/example/agent/engine/QueryEngineHookTest.java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.hook.*;
import org.example.agent.model.*;
import org.example.agent.tool.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineHookTest {

    private static ModelClient endTurnModel() {
        return req -> new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 0, 0);
    }

    private static ModelClient toolThenEndModel(String toolName, Map<String, Object> input) {
        int[] count = {0};
        return req -> {
            count[0]++;
            if (count[0] == 1)
                return new ModelResponse(
                        List.of(new ContentBlock.ToolUse("tid1", toolName, input)),
                        StopReason.TOOL_USE, 0, 0);
            return new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 0, 0);
        };
    }

    private static ToolRegistry registryWithEcho(String toolName) {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition(toolName, "", Map.of());
            }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("echo result");
            }
        });
        return registry;
    }

    @Test
    void session_start_hook_is_called_before_first_model_call() {
        var called = new AtomicBoolean(false);
        HookRunner hook = event -> {
            if (event.name() == HookEventName.SESSION_START) called.set(true);
            return HookResult.ok();
        };

        var engine = new QueryEngine(endTurnModel(), new ToolRegistry(), hook);
        engine.run(new QueryParams(List.of(Message.user("hi")), null, null, null, 5));

        assertTrue(called.get(), "SessionStart hook must be called");
    }

    @Test
    void session_start_hook_receives_system_prompt_in_payload() {
        List<Map<String, Object>> payloads = new ArrayList<>();
        HookRunner hook = event -> {
            if (event.name() == HookEventName.SESSION_START) payloads.add(event.payload());
            return HookResult.ok();
        };

        var engine = new QueryEngine(endTurnModel(), new ToolRegistry(), hook);
        engine.run(new QueryParams(List.of(Message.user("hi")), "my-system-prompt", null, null, 5));

        assertFalse(payloads.isEmpty());
        assertEquals("my-system-prompt", payloads.get(0).get("system_prompt"));
    }

    @Test
    void post_tool_exit_2_injection_message_appears_in_conversation() {
        HookRunner hook = event -> {
            if (event.name() == HookEventName.POST_TOOL_USE) return HookResult.inject("hook says: noted");
            return HookResult.ok();
        };

        var registry = registryWithEcho("bash");
        var engine = new QueryEngine(toolThenEndModel("bash", Map.of()), registry, hook);
        var result = (QueryResult.Success) engine.run(
                new QueryParams(List.of(Message.user("run bash")), null, null, null, 5));

        var userTexts = result.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .toList();

        assertTrue(userTexts.stream().anyMatch(t -> t.contains("hook says: noted")),
                "Injection message should appear as user message. Texts: " + userTexts);
    }

    @Test
    void no_hook_runner_runs_normally_without_error() {
        var engine = new QueryEngine(endTurnModel(), new ToolRegistry());
        var result = engine.run(new QueryParams(List.of(Message.user("hi")), null, null, null, 5));
        assertInstanceOf(QueryResult.Success.class, result);
    }
}
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
mvn test -Dtest=QueryEngineHookTest -q
```

预期：编译错误，`new QueryEngine(..., hook)` 构造函数不存在。

- [ ] **Step 3: 更新 QueryEngine**

**3a. 在 `QueryEngine.java` 开头添加 imports：**

```java
import org.example.agent.hook.HookEvent;
import org.example.agent.hook.HookEventName;
import org.example.agent.hook.HookRunner;
```

**3b. 在字段声明区（`userConfirmation` 之后）追加：**

```java
private final HookRunner hookRunner;
```

**3c. 在私有 canonical 构造函数签名追加 `HookRunner hookRunner` 参数，并赋值：**

将现有 private 构造函数：
```java
private QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                    SkillRegistry skillRegistry, ContextCompactor compactor,
                    ExecutorService executor,
                    PermissionChecker permissionChecker, UserConfirmation userConfirmation) {
```
改为：
```java
private QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                    SkillRegistry skillRegistry, ContextCompactor compactor,
                    ExecutorService executor,
                    PermissionChecker permissionChecker, UserConfirmation userConfirmation,
                    HookRunner hookRunner) {
```
并在构造函数体末尾（`toolRegistry.register(...)` 之前或之后）追加：
```java
this.hookRunner = hookRunner;
```

**3d. 更新所有现有 public/package 构造函数，在调用 `this(...)` 时末尾追加 `null`：**

```java
public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
    this(modelClient, toolRegistry, null, defaultCompactor(), ForkJoinPool.commonPool(), null, null, null);
}

QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, ExecutorService executor) {
    this(modelClient, toolRegistry, null, defaultCompactor(), executor, null, null, null);
}

public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                   SkillRegistry skillRegistry) {
    this(modelClient, toolRegistry, skillRegistry, defaultCompactor(), ForkJoinPool.commonPool(), null, null, null);
}

QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
            ContextCompactor compactor, ExecutorService executor) {
    this(modelClient, toolRegistry, null, compactor, executor, null, null, null);
}

public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                   PermissionChecker permissionChecker, UserConfirmation userConfirmation) {
    this(modelClient, toolRegistry, null, defaultCompactor(),
            ForkJoinPool.commonPool(), permissionChecker, userConfirmation, null);
}
```

**3e. 新增 public HookRunner 构造函数：**

```java
public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, HookRunner hookRunner) {
    this(modelClient, toolRegistry, null, defaultCompactor(),
            ForkJoinPool.commonPool(), null, null, hookRunner);
}
```

**3f. 更新 `run()` 方法的 context 初始化（替换现有的三行 currentCtx 赋值）：**

现有代码：
```java
var baseCtx = ToolUseContext.defaults(System.getProperty("user.dir"));
currentCtx = (permissionChecker != null)
        ? baseCtx.withPermissions(permissionChecker, userConfirmation)
        : baseCtx;
```

替换为：
```java
var baseCtx = ToolUseContext.defaults(System.getProperty("user.dir"));
var ctx = baseCtx;
if (permissionChecker != null) ctx = ctx.withPermissions(permissionChecker, userConfirmation);
if (hookRunner != null) ctx = ctx.withHookRunner(hookRunner);
currentCtx = ctx;

// SessionStart hook
if (hookRunner != null) {
    hookRunner.run(new HookEvent(HookEventName.SESSION_START,
            java.util.Map.of("system_prompt", String.valueOf(params.systemPrompt()))));
}
```

**3g. 在 `run()` 的 TOOL_USE 分支中，在 `buildToolResultMessage(...)` 之后追加注入消息处理：**

现有代码（在 TOOL_USE 分支末尾）：
```java
currentState.appendMessage(
        buildToolResultMessage(execResult.toolResults(),
                currentCtx.planningState().needsReminder()));
currentState.setLastTransition(
        new TransitionReason.ToolResultContinuation(execResult.toolResults()));
currentState.incrementTurn();
```

替换为：
```java
currentState.appendMessage(
        buildToolResultMessage(execResult.toolResults(),
                currentCtx.planningState().needsReminder()));
for (String msg : execResult.injectionMessages()) {
    currentState.appendMessage(Message.user(msg));
}
currentState.setLastTransition(
        new TransitionReason.ToolResultContinuation(execResult.toolResults()));
currentState.incrementTurn();
```

- [ ] **Step 4: 运行新测试，确认通过**

```bash
mvn test -Dtest=QueryEngineHookTest -q
```

预期：BUILD SUCCESS，4 tests passed。

- [ ] **Step 5: 运行全部测试，确认无回归**

```bash
mvn test -q
```

预期：BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/engine/QueryEngineHookTest.java
git commit -m "feat(hook): integrate SessionStart hook and injection messages in QueryEngine"
```

---

## Task 7: hooks.json 示例资源文件

**Files:**
- Create: `src/main/resources/hooks.json`

- [ ] **Step 1: 创建 hooks.json 示例文件**

```json
// src/main/resources/hooks.json
{
  "SessionStart": [],
  "PreToolUse": [],
  "PostToolUse": []
}
```

（空数组 = 该事件无 hook，agent 正常运行。用户按需填入脚本路径。）

- [ ] **Step 2: 运行全部测试，确认无影响**

```bash
mvn test -q
```

注意：`HookConfigTest.load_from_classpath_finds_test_hooks_json()` 使用 `src/test/resources/hooks.json`（测试 classpath 优先），主 classpath 的 `hooks.json` 在生产环境生效。

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/hooks.json
git commit -m "feat(hook): add hooks.json example resource for s08"
```

---

## 完成验收

- [ ] 运行完整测试套件

```bash
mvn test
```

预期输出：
```
[INFO] Tests run: N, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

- [ ] 确认新测试类均已覆盖：`HookResultTest`、`HookConfigTest`、`ShellHookRunnerTest`、`ToolExecutionRuntimeHookTest`、`QueryEngineHookTest`
