# Tool Execution Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade `QueryEngine.collectResults()` into a proper execution runtime that partitions tool calls into concurrent-safe and exclusive batches, runs safe batches in parallel via `CompletableFuture`, collects results in original order, and queues context modifiers for in-order apply.

**Architecture:** A new `ToolExecutionRuntime` class handles all scheduling logic; `QueryEngine` delegates tool execution to it and threads the updated `ToolUseContext` through the agentic loop. `ToolRouter` grows two new methods (`routeToEnvelope`, `isConcurrencySafe`) that `ToolExecutionRuntime` uses internally.

**Tech Stack:** Java 21, JUnit 5, `java.util.concurrent` (`CompletableFuture`, `ExecutorService`)

---

## File Map

| Action | Path | Purpose |
|--------|------|---------|
| Modify | `src/main/java/org/example/agent/tool/Tool.java` | Add `default boolean isConcurrencySafe()` |
| Modify | `src/main/java/org/example/agent/tool/ReadFileTool.java` | Override `isConcurrencySafe()` → `true` |
| Modify | `src/main/java/org/example/agent/tool/ToolUseContext.java` | Add `withNotifications(List<String>)` copy factory |
| Modify | `src/main/java/org/example/agent/tool/ToolResultEnvelope.java` | Add `contextModifier` field |
| Modify | `src/main/java/org/example/agent/tool/ToolRouter.java` | Add `routeToEnvelope()` and `isConcurrencySafe()` |
| Create | `src/main/java/org/example/agent/tool/ToolExecutionBatch.java` | Record: `List<ToolUse>` + `boolean concurrencySafe` |
| Create | `src/main/java/org/example/agent/tool/TrackedTool.java` | Record: tool id/name/status/result |
| Create | `src/main/java/org/example/agent/tool/ExecutionResult.java` | Record: results list + updated context |
| Create | `src/main/java/org/example/agent/tool/ToolExecutionRuntime.java` | Core: partition, execute, collect, apply modifiers |
| Modify | `src/main/java/org/example/agent/engine/QueryEngine.java` | Delegate to runtime; thread ctx through loop |
| Modify | `src/test/java/org/example/agent/tool/ToolResultEnvelopeTest.java` | Add contextModifier tests |
| Modify | `src/test/java/org/example/agent/tool/ToolRouterTest.java` | Add routeToEnvelope + isConcurrencySafe tests |
| Create | `src/test/java/org/example/agent/tool/ToolExecutionRuntimeTest.java` | Full runtime tests |
| Modify | `src/test/java/org/example/agent/engine/QueryEngineTest.java` | Verify existing tests still pass after rewire |

---

## Task 1: Add `isConcurrencySafe()` to `Tool` interface and `ReadFileTool`

**Files:**
- Modify: `src/main/java/org/example/agent/tool/Tool.java`
- Modify: `src/main/java/org/example/agent/tool/ReadFileTool.java`

- [ ] **Step 1: Add default method to `Tool.java`**

Replace the entire file:

```java
package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;
import java.util.Map;

public interface Tool {
    ToolDefinition definition();
    ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx);

    default boolean isConcurrencySafe() { return false; }
}
```

- [ ] **Step 2: Override in `ReadFileTool.java`**

Add after the `execute()` method (before the closing `}`):

```java
    @Override
    public boolean isConcurrencySafe() { return true; }
```

- [ ] **Step 3: Run existing tests to verify nothing broke**

```
./mvnw test
```

Expected: all tests PASS (no call sites changed).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/agent/tool/Tool.java \
        src/main/java/org/example/agent/tool/ReadFileTool.java
git commit -m "feat(tool): add isConcurrencySafe() to Tool interface; ReadFileTool returns true"
```

---

## Task 2: Extend `ToolUseContext` and `ToolResultEnvelope`

**Files:**
- Modify: `src/main/java/org/example/agent/tool/ToolUseContext.java`
- Modify: `src/main/java/org/example/agent/tool/ToolResultEnvelope.java`
- Modify: `src/test/java/org/example/agent/tool/ToolResultEnvelopeTest.java`

- [ ] **Step 1: Write failing tests for new fields**

Replace `ToolResultEnvelopeTest.java`:

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

    @Test
    void success_has_empty_context_modifier() {
        var e = ToolResultEnvelope.success("hello");
        assertTrue(e.contextModifier().isEmpty());
    }

    @Test
    void error_has_empty_context_modifier() {
        var e = ToolResultEnvelope.error("boom");
        assertTrue(e.contextModifier().isEmpty());
    }
}
```

- [ ] **Step 2: Run tests to verify the new tests fail**

```
./mvnw test -Dtest=ToolResultEnvelopeTest
```

Expected: FAIL — `contextModifier()` method does not exist.

- [ ] **Step 3: Add `withNotifications()` to `ToolUseContext.java`**

Add this method before the closing `}`:

```java
    public ToolUseContext withNotifications(List<String> notifications) {
        return new ToolUseContext(permissionContext, mcpClients, appState,
                List.copyOf(notifications), cwd);
    }
```

- [ ] **Step 4: Replace `ToolResultEnvelope.java` with contextModifier field**

```java
package org.example.agent.tool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

public record ToolResultEnvelope(
        boolean ok,
        String content,
        boolean isError,
        List<Object> attachments,
        Optional<UnaryOperator<ToolUseContext>> contextModifier
) {
    public ToolResultEnvelope {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(contextModifier, "contextModifier must not be null");
    }

    public static ToolResultEnvelope success(String content) {
        return new ToolResultEnvelope(true, content, false, List.of(), Optional.empty());
    }

    public static ToolResultEnvelope error(String message) {
        return new ToolResultEnvelope(false, message, true, List.of(), Optional.empty());
    }
}
```

- [ ] **Step 5: Run all tests**

```
./mvnw test
```

Expected: all tests PASS (factories updated; all existing call sites use the factories).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/tool/ToolUseContext.java \
        src/main/java/org/example/agent/tool/ToolResultEnvelope.java \
        src/test/java/org/example/agent/tool/ToolResultEnvelopeTest.java
git commit -m "feat(tool): add contextModifier to ToolResultEnvelope; withNotifications() to ToolUseContext"
```

---

## Task 3: Add `routeToEnvelope()` and `isConcurrencySafe()` to `ToolRouter`

**Files:**
- Modify: `src/main/java/org/example/agent/tool/ToolRouter.java`
- Modify: `src/test/java/org/example/agent/tool/ToolRouterTest.java`

- [ ] **Step 1: Write failing tests**

Append these tests to `ToolRouterTest.java` (inside the class, before the closing `}`):

```java
    @Test
    void route_to_envelope_returns_envelope_for_native_tool() {
        var router = new ToolRouter(registryWithEcho());
        var toolUse = new ContentBlock.ToolUse("id-4", "echo", Map.of("text", "world"));
        var envelope = router.routeToEnvelope(toolUse, ctx);
        assertTrue(envelope.ok());
        assertEquals("world", envelope.content());
    }

    @Test
    void route_to_envelope_throws_unknown_tool_exception() {
        var router = new ToolRouter(new ToolRegistry());
        var toolUse = new ContentBlock.ToolUse("id-5", "missing", Map.of());
        assertThrows(UnknownToolException.class, () -> router.routeToEnvelope(toolUse, ctx));
    }

    @Test
    void route_to_envelope_throws_unsupported_for_mcp() {
        var router = new ToolRouter(new ToolRegistry());
        var toolUse = new ContentBlock.ToolUse("id-6", "mcp__db__query", Map.of());
        assertThrows(UnsupportedOperationException.class, () -> router.routeToEnvelope(toolUse, ctx));
    }

    @Test
    void is_concurrency_safe_returns_true_for_safe_tool() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() { return new ToolDefinition("safe_tool", "", Map.of()); }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("ok");
            }
            @Override
            public boolean isConcurrencySafe() { return true; }
        });
        assertTrue(new ToolRouter(registry).isConcurrencySafe("safe_tool"));
    }

    @Test
    void is_concurrency_safe_returns_false_for_unsafe_tool() {
        assertFalse(new ToolRouter(registryWithEcho()).isConcurrencySafe("echo"));
    }

    @Test
    void is_concurrency_safe_returns_false_for_unknown_tool() {
        assertFalse(new ToolRouter(new ToolRegistry()).isConcurrencySafe("nonexistent"));
    }

    @Test
    void is_concurrency_safe_returns_false_for_mcp_tool() {
        assertFalse(new ToolRouter(new ToolRegistry()).isConcurrencySafe("mcp__any__tool"));
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
./mvnw test -Dtest=ToolRouterTest
```

Expected: FAIL — `routeToEnvelope` and `isConcurrencySafe` do not exist.

- [ ] **Step 3: Add the two methods to `ToolRouter.java`**

Append before the closing `}` of the class:

```java
    public ToolResultEnvelope routeToEnvelope(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        if (toolUse.name().startsWith("mcp__")) {
            throw new UnsupportedOperationException("MCP tools not implemented (s19)");
        }
        var tool = registry.get(toolUse.name());
        if (tool == null) throw new UnknownToolException(toolUse.name());
        return tool.execute(toolUse.input(), ctx);
    }

    public boolean isConcurrencySafe(String toolName) {
        if (toolName.startsWith("mcp__")) return false;
        var tool = registry.get(toolName);
        return tool != null && tool.isConcurrencySafe();
    }
```

- [ ] **Step 4: Run tests**

```
./mvnw test -Dtest=ToolRouterTest
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/ToolRouter.java \
        src/test/java/org/example/agent/tool/ToolRouterTest.java
git commit -m "feat(tool): add routeToEnvelope() and isConcurrencySafe() to ToolRouter"
```

---

## Task 4: Create supporting records

**Files:**
- Create: `src/main/java/org/example/agent/tool/ToolExecutionBatch.java`
- Create: `src/main/java/org/example/agent/tool/TrackedTool.java`
- Create: `src/main/java/org/example/agent/tool/ExecutionResult.java`

No tests for these — they are plain data records verified by usage in later tasks.

- [ ] **Step 1: Create `ToolExecutionBatch.java`**

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import java.util.List;

public record ToolExecutionBatch(
        List<ContentBlock.ToolUse> toolUses,
        boolean concurrencySafe
) {}
```

- [ ] **Step 2: Create `TrackedTool.java`**

```java
package org.example.agent.tool;

public record TrackedTool(
        String id,
        String name,
        Status status,
        ToolResultEnvelope result
) {
    public enum Status { QUEUED, EXECUTING, COMPLETED }
}
```

- [ ] **Step 3: Create `ExecutionResult.java`**

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import java.util.List;

public record ExecutionResult(
        List<ContentBlock.ToolResult> toolResults,
        ToolUseContext updatedContext
) {}
```

- [ ] **Step 4: Compile to verify no errors**

```
./mvnw compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/ToolExecutionBatch.java \
        src/main/java/org/example/agent/tool/TrackedTool.java \
        src/main/java/org/example/agent/tool/ExecutionResult.java
git commit -m "feat(tool): add ToolExecutionBatch, TrackedTool, ExecutionResult records"
```

---

## Task 5: Implement `ToolExecutionRuntime`

**Files:**
- Create: `src/main/java/org/example/agent/tool/ToolExecutionRuntime.java`
- Create: `src/test/java/org/example/agent/tool/ToolExecutionRuntimeTest.java`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/org/example/agent/tool/ToolExecutionRuntimeTest.java`:

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionRuntimeTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ToolUseContext ctx = ToolUseContext.defaults(".");

    @AfterEach
    void tearDown() { executor.shutdownNow(); }

    private Tool simpleTool(String name, String output, boolean safe) {
        return new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition(name, "", Map.of()); }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success(output);
            }
            @Override public boolean isConcurrencySafe() { return safe; }
        };
    }

    private ToolExecutionRuntime runtime(Tool... tools) {
        var registry = new ToolRegistry();
        for (var t : tools) registry.register(t);
        return new ToolExecutionRuntime(new ToolRouter(registry), executor);
    }

    @Test
    void partition_groups_consecutive_safe_tools_and_isolates_unsafe() {
        var rt = runtime(
            simpleTool("r1", "a", true), simpleTool("r2", "b", true),
            simpleTool("w1", "c", false), simpleTool("r3", "d", true)
        );
        var toolUses = List.of(
            new ContentBlock.ToolUse("1", "r1", Map.of()),
            new ContentBlock.ToolUse("2", "r2", Map.of()),
            new ContentBlock.ToolUse("3", "w1", Map.of()),
            new ContentBlock.ToolUse("4", "r3", Map.of())
        );

        var batches = rt.partition(toolUses);

        assertEquals(3, batches.size());
        assertTrue(batches.get(0).concurrencySafe());
        assertEquals(2, batches.get(0).toolUses().size());
        assertFalse(batches.get(1).concurrencySafe());
        assertEquals(1, batches.get(1).toolUses().size());
        assertTrue(batches.get(2).concurrencySafe());
        assertEquals(1, batches.get(2).toolUses().size());
    }

    @Test
    void results_returned_in_original_order() {
        var rt = runtime(simpleTool("t1", "first", true), simpleTool("t2", "second", true));
        var toolUses = List.of(
            new ContentBlock.ToolUse("id1", "t1", Map.of()),
            new ContentBlock.ToolUse("id2", "t2", Map.of())
        );

        var result = rt.execute(toolUses, ctx);

        assertEquals(2, result.toolResults().size());
        assertEquals("id1", result.toolResults().get(0).toolUseId());
        assertEquals("first", result.toolResults().get(0).content());
        assertEquals("id2", result.toolResults().get(1).toolUseId());
        assertEquals("second", result.toolResults().get(1).content());
    }

    @Test
    void safe_batch_executes_tools_concurrently() {
        var latch = new CountDownLatch(2);
        var bothStarted = new AtomicBoolean(false);

        Tool blocking1 = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("b1", "", Map.of()); }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                latch.countDown();
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ToolResultEnvelope.success("b1");
            }
            @Override public boolean isConcurrencySafe() { return true; }
        };
        Tool blocking2 = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("b2", "", Map.of()); }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                latch.countDown();
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                bothStarted.set(true);
                return ToolResultEnvelope.success("b2");
            }
            @Override public boolean isConcurrencySafe() { return true; }
        };

        var registry = new ToolRegistry();
        registry.register(blocking1);
        registry.register(blocking2);
        var rt = new ToolExecutionRuntime(new ToolRouter(registry), executor);

        var result = rt.execute(List.of(
            new ContentBlock.ToolUse("1", "b1", Map.of()),
            new ContentBlock.ToolUse("2", "b2", Map.of())
        ), ctx);

        assertTrue(bothStarted.get(), "both tools must start before either completes");
        assertEquals(2, result.toolResults().size());
    }

    @Test
    void context_modifiers_applied_in_original_order() {
        Tool toolA = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("tool_a", "", Map.of()); }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                UnaryOperator<ToolUseContext> mod = c -> {
                    var n = new ArrayList<>(c.notifications());
                    n.add("A");
                    return c.withNotifications(n);
                };
                return new ToolResultEnvelope(true, "a", false, List.of(), Optional.of(mod));
            }
        };
        Tool toolB = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("tool_b", "", Map.of()); }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                UnaryOperator<ToolUseContext> mod = c -> {
                    var n = new ArrayList<>(c.notifications());
                    n.add("B");
                    return c.withNotifications(n);
                };
                return new ToolResultEnvelope(true, "b", false, List.of(), Optional.of(mod));
            }
        };

        var registry = new ToolRegistry();
        registry.register(toolA);
        registry.register(toolB);
        var rt = new ToolExecutionRuntime(new ToolRouter(registry), executor);

        var result = rt.execute(List.of(
            new ContentBlock.ToolUse("1", "tool_a", Map.of()),
            new ContentBlock.ToolUse("2", "tool_b", Map.of())
        ), ctx);

        assertEquals(List.of("A", "B"), result.updatedContext().notifications());
    }

    @Test
    void unknown_tool_returns_error_result() {
        var rt = runtime();
        var result = rt.execute(List.of(new ContentBlock.ToolUse("x", "nonexistent", Map.of())), ctx);
        assertEquals(1, result.toolResults().size());
        assertTrue(result.toolResults().get(0).content().contains("nonexistent"));
    }

    @Test
    void mcp_tool_returns_error_result() {
        var rt = runtime();
        var result = rt.execute(List.of(new ContentBlock.ToolUse("y", "mcp__db__query", Map.of())), ctx);
        assertEquals(1, result.toolResults().size());
        assertEquals("MCP tools not yet supported", result.toolResults().get(0).content());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./mvnw test -Dtest=ToolExecutionRuntimeTest
```

Expected: FAIL — `ToolExecutionRuntime` class does not exist.

- [ ] **Step 3: Create `ToolExecutionRuntime.java`**

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class ToolExecutionRuntime {

    private final ToolRouter router;
    private final ExecutorService executor;

    public ToolExecutionRuntime(ToolRouter router, ExecutorService executor) {
        this.router = router;
        this.executor = executor;
    }

    public ExecutionResult execute(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        var batches = partition(toolUses);
        var allResults = new ArrayList<ContentBlock.ToolResult>();
        var currentCtx = ctx;

        for (var batch : batches) {
            var envelopes = executeBatch(batch, currentCtx);
            for (int i = 0; i < envelopes.size(); i++) {
                var envelope = envelopes.get(i);
                var toolUse = batch.toolUses().get(i);
                allResults.add(new ContentBlock.ToolResult(toolUse.id(), envelope.content()));
                if (envelope.contextModifier().isPresent()) {
                    currentCtx = envelope.contextModifier().get().apply(currentCtx);
                }
            }
        }
        return new ExecutionResult(List.copyOf(allResults), currentCtx);
    }

    List<ToolExecutionBatch> partition(List<ContentBlock.ToolUse> toolUses) {
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

    private List<ToolResultEnvelope> executeBatch(ToolExecutionBatch batch, ToolUseContext ctx) {
        if (batch.concurrencySafe()) {
            return executeConcurrently(batch.toolUses(), ctx);
        }
        return executeSerially(batch.toolUses(), ctx);
    }

    private List<ToolResultEnvelope> executeConcurrently(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        var futures = toolUses.stream()
                .map(tu -> CompletableFuture.supplyAsync(() -> routeSafely(tu, ctx), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private List<ToolResultEnvelope> executeSerially(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        return toolUses.stream().map(tu -> routeSafely(tu, ctx)).toList();
    }

    private ToolResultEnvelope routeSafely(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        try {
            return router.routeToEnvelope(toolUse, ctx);
        } catch (UnknownToolException e) {
            return ToolResultEnvelope.error(e.getMessage());
        } catch (UnsupportedOperationException e) {
            return ToolResultEnvelope.error("MCP tools not yet supported");
        }
    }
}
```

- [ ] **Step 4: Run tests**

```
./mvnw test -Dtest=ToolExecutionRuntimeTest
```

Expected: all PASS.

- [ ] **Step 5: Run full test suite**

```
./mvnw test
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/tool/ToolExecutionRuntime.java \
        src/test/java/org/example/agent/tool/ToolExecutionRuntimeTest.java
git commit -m "feat(tool): implement ToolExecutionRuntime with concurrent batching and ordered context modifiers"
```

---

## Task 6: Rewire `QueryEngine` to delegate to `ToolExecutionRuntime`

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Modify: `src/test/java/org/example/agent/engine/QueryEngineTest.java`

- [ ] **Step 1: Run existing `QueryEngineTest` to establish baseline**

```
./mvnw test -Dtest=QueryEngineTest
```

Expected: all PASS. Note the test count — it must not drop after the rewire.

- [ ] **Step 2: Replace `QueryEngine.java`**

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelClient;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.*;

import java.util.List;
import java.util.concurrent.ForkJoinPool;

public class QueryEngine {

    private static final String CONTINUE_PROMPT = "Please continue.";

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionRuntime runtime;

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        var router = new ToolRouter(toolRegistry);
        this.runtime = new ToolExecutionRuntime(router, ForkJoinPool.commonPool());
    }

    public QueryResult run(QueryParams params) {
        var state = QueryState.from(params);
        var ctx = ToolUseContext.defaults(System.getProperty("user.dir"));
        while (true) {
            var response = modelClient.call(buildRequest(state, params));

            if (response.stopReason() == StopReason.TOOL_USE) {
                var toolUses = response.content().stream()
                        .filter(b -> b instanceof ContentBlock.ToolUse)
                        .map(b -> (ContentBlock.ToolUse) b)
                        .toList();
                var execResult = runtime.execute(toolUses, ctx);
                ctx = execResult.updatedContext();
                advance(state, new TransitionReason.ToolResultContinuation(execResult.toolResults()), response);
            } else {
                var transition = decide(state, response);
                if (transition == null) {
                    state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                    return new QueryResult.Success(state.messages(), state.turnCount());
                }
                advance(state, transition, response);
            }
        }
    }

    private TransitionReason decide(QueryState state, ModelResponse response) {
        return switch (response.stopReason()) {
            case END_TURN -> null;
            case TOOL_USE -> throw new IllegalStateException("TOOL_USE handled in run()");
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

    private Message buildToolResultMessage(List<ContentBlock.ToolResult> results) {
        return new Message(Role.USER, List.copyOf(results));
    }
}
```

- [ ] **Step 3: Run `QueryEngineTest` to verify all existing tests still pass**

```
./mvnw test -Dtest=QueryEngineTest
```

Expected: same number of tests, all PASS. The `greet` tool (no `isConcurrencySafe` override) runs as an exclusive batch — same observable behavior.

- [ ] **Step 4: Run full test suite**

```
./mvnw test
```

Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/engine/QueryEngine.java
git commit -m "refactor(engine): delegate tool execution to ToolExecutionRuntime; thread ctx through run() loop"
```
