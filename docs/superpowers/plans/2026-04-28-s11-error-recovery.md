# s11 Error Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给主循环加上三条恢复路径（续写/压缩/退避），把"报错就崩"升级为"先判断错误类型，再选恢复路径"。

**Architecture:** transport 抖动通过 `classifyException()` 分类 → `TransportRetry` transition → `advance()` 执行指数退避。续写和压缩路径在 s10 已实现，本次只补 transport 路径。`QueryState` 新增 `transportRetryCount` 字段对齐现有 `continuationCount` 模式。`QueryEngine` 新增 `backoffUnitMs` 字段支持测试快速通过。

**Tech Stack:** Java 21、JUnit 5、项目现有 `QueryEngine` / `QueryState` / `TransitionReason`

---

## 改动文件清单

| 文件 | 动作 |
|------|------|
| `src/main/java/org/example/agent/core/QueryState.java` | 新增 `transportRetryCount` 字段 + 读写方法 |
| `src/main/java/org/example/agent/engine/QueryEngine.java` | 新增 `backoffUnitMs` 字段、`TRANSPORT_RETRY_BUDGET` 常量、`TRANSIENT_KEYWORDS` 常量、`classifyException()`、`backoffDelay()`；修改 `run()` 加 try-catch；补全 `advance()` TransportRetry case；新增 package-private 测试构造器 |
| `src/test/java/org/example/agent/core/QueryStateTest.java` | 新增 `transportRetryCount` 相关测试 |
| `src/test/java/org/example/agent/engine/QueryEngineErrorRecoveryTest.java` | 创建，含 4 个集成场景 |

---

## Task 1: QueryState — transportRetryCount 字段

**Files:**
- Modify: `src/main/java/org/example/agent/core/QueryState.java`
- Test: `src/test/java/org/example/agent/core/QueryStateTest.java`

- [ ] **Step 1: 写失败测试**

在 `QueryStateTest.java` 的现有测试类末尾追加：

```java
@Test
void transport_retry_count_starts_at_zero() {
    var state = QueryState.from(minimalParams());
    assertEquals(0, state.transportRetryCount());
}

@Test
void increment_transport_retry_increments_count() {
    var state = QueryState.from(minimalParams());
    state.incrementTransportRetry();
    state.incrementTransportRetry();
    assertEquals(2, state.transportRetryCount());
}
```

- [ ] **Step 2: 运行测试，确认失败**

```
./mvnw test -pl . -Dtest=QueryStateTest -q
```

期望：`transport_retry_count_starts_at_zero` 和 `increment_transport_retry_increments_count` 报编译错误或 NoSuchMethodError。

- [ ] **Step 3: 在 QueryState 新增字段和方法**

在 `QueryState.java` 的 `private int continuationCount = 0;` 下方插入：

```java
private int transportRetryCount = 0;
```

在 `public void incrementContinuation()` 方法下方插入：

```java
public int transportRetryCount() { return transportRetryCount; }

public void incrementTransportRetry() { transportRetryCount++; }
```

- [ ] **Step 4: 运行测试，确认通过**

```
./mvnw test -pl . -Dtest=QueryStateTest -q
```

期望：所有测试 PASS，包括原有测试未受影响。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/org/example/agent/core/QueryState.java \
        src/test/java/org/example/agent/core/QueryStateTest.java
git commit -m "feat(s11): add transportRetryCount to QueryState"
```

---

## Task 2: QueryEngine — 错误恢复完整实现

所有引擎改动在同一 Task 完成（各步骤测试驱动）：`backoffUnitMs` 注入、`classifyException()`、`backoffDelay()`、`run()` try-catch、`advance()` TransportRetry case。

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Create: `src/test/java/org/example/agent/engine/QueryEngineErrorRecoveryTest.java`

### Step 1: 创建测试文件（全部失败场景）

创建 `src/test/java/org/example/agent/engine/QueryEngineErrorRecoveryTest.java`：

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineErrorRecoveryTest {

    private QueryParams params(String msg) {
        return new QueryParams(List.of(Message.user(msg)), "sys", null, null, null);
    }

    private ModelResponse endTurn() {
        return new ModelResponse(
                List.of(new ContentBlock.Text("Done.")), StopReason.END_TURN, 10, 5);
    }

    // ---------------------------------------------------------------
    // 场景 1: transport 抖动 → 重试 → 成功
    // ---------------------------------------------------------------
    @Test
    void transient_exception_triggers_retry_then_succeeds() {
        var callCount = new int[]{0};
        var engine = new QueryEngine(
                request -> {
                    callCount[0]++;
                    if (callCount[0] == 1) throw new RuntimeException("connection reset");
                    return endTurn();
                },
                new ToolRegistry(),
                0L   // 0ms backoff for test speed
        );

        var result = engine.run(params("hi"));

        assertInstanceOf(QueryResult.Success.class, result);
        assertEquals(2, callCount[0]);
    }

    // ---------------------------------------------------------------
    // 场景 2: 预算耗尽 → 向上抛出原始异常
    // ---------------------------------------------------------------
    @Test
    void transport_budget_exhausted_throws_original_exception() {
        var engine = new QueryEngine(
                request -> { throw new RuntimeException("connection timeout"); },
                new ToolRegistry(),
                0L
        );

        var ex = assertThrows(RuntimeException.class, () -> engine.run(params("hi")));
        assertTrue(ex.getMessage().contains("connection timeout"));
    }

    // ---------------------------------------------------------------
    // 场景 3: 不可恢复异常 → 立即抛出，不重试
    // ---------------------------------------------------------------
    @Test
    void non_transient_exception_throws_immediately_without_retry() {
        var callCount = new int[]{0};
        var engine = new QueryEngine(
                request -> {
                    callCount[0]++;
                    throw new RuntimeException("internal server error");
                },
                new ToolRegistry(),
                0L
        );

        assertThrows(RuntimeException.class, () -> engine.run(params("hi")));
        assertEquals(1, callCount[0]); // 只调用一次，不重试
    }

    // ---------------------------------------------------------------
    // 场景 4: 已有续写路径未被破坏（MAX_TOKENS → END_TURN）
    // ---------------------------------------------------------------
    @Test
    void max_tokens_recovery_still_works_after_s11_changes() {
        var responses = new ModelResponse[]{
                new ModelResponse(List.of(new ContentBlock.Text("Part 1...")),
                        StopReason.MAX_TOKENS, 10, 100),
                new ModelResponse(List.of(new ContentBlock.Text("...Part 2.")),
                        StopReason.END_TURN, 10, 20)
        };
        var idx = new int[]{0};

        var engine = new QueryEngine(request -> responses[idx[0]++], new ToolRegistry(), 0L);
        var result = engine.run(params("Write a long essay"));

        assertInstanceOf(QueryResult.Success.class, result);
        assertEquals(2, idx[0]);
    }
}
```

- [ ] **Step 2: 运行测试，确认编译失败**

```
./mvnw test -pl . -Dtest=QueryEngineErrorRecoveryTest -q
```

期望：编译错误 `no suitable constructor found for QueryEngine(ModelClient, ToolRegistry, long)`。

- [ ] **Step 3: 在 QueryEngine 新增 `backoffUnitMs` 字段和 package-private 测试构造器**

在 `QueryEngine.java` 的字段区（`private final MessagePipeline messagePipeline;` 下方）新增：

```java
private final long backoffUnitMs;
```

将 `private QueryEngine(...)` 主构造器签名改为接收 `long backoffUnitMs`，并在末尾赋值：

```java
private QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                    SkillRegistry skillRegistry, ContextCompactor compactor,
                    ExecutorService executor,
                    PermissionChecker permissionChecker, UserConfirmation userConfirmation,
                    HookRunner hookRunner, MemoryStore memoryStore,
                    long backoffUnitMs) {
    // ... 保留原有字段赋值 ...
    this.backoffUnitMs = backoffUnitMs;
    // ... 保留原有 router / runtime / toolRegistry / promptBuilder / messagePipeline 初始化 ...
}
```

所有现有的 public/package-private 构造器在委托调用时末尾追加 `1000L`，例如：

```java
public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
    this(modelClient, toolRegistry, null, defaultCompactor(),
         ForkJoinPool.commonPool(), null, null, null, null, 1000L);
}
```

（其余所有现有构造器同样在末尾加 `, 1000L`。）

新增 package-private 测试构造器：

```java
QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, long backoffUnitMs) {
    this(modelClient, toolRegistry, null, defaultCompactor(),
         ForkJoinPool.commonPool(), null, null, null, null, backoffUnitMs);
}
```

- [ ] **Step 4: 运行测试，确认编译通过但测试仍然失败（无 try-catch）**

```
./mvnw test -pl . -Dtest=QueryEngineErrorRecoveryTest -q
```

期望：编译通过，但 `transient_exception_triggers_retry_then_succeeds` 等测试因 RuntimeException 未捕获而 FAIL。

- [ ] **Step 5: 在 QueryEngine 新增常量和 `classifyException()`**

在 `private static final String CONTINUE_PROMPT` 附近新增常量：

```java
private static final int TRANSPORT_RETRY_BUDGET = 3;

private static final List<String> TRANSIENT_KEYWORDS =
        List.of("timeout", "rate", "unavailable", "connection");
```

在 `buildRequest()` 方法下方新增两个私有方法：

```java
private static TransitionReason classifyException(int attempt, Throwable e) {
    var msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
    if (TRANSIENT_KEYWORDS.stream().anyMatch(msg::contains)) {
        return new TransitionReason.TransportRetry(attempt, e);
    }
    return null;
}

private double backoffDelay(int attempt) {
    return Math.min((double) backoffUnitMs * Math.pow(2, attempt - 1), 30_000.0);
}
```

- [ ] **Step 6: 修改 `run()` 加 try-catch**

将 `run()` 中：

```java
var response = modelClient.call(buildRequest(currentState, params));
```

替换为：

```java
ModelResponse response;
try {
    response = modelClient.call(buildRequest(currentState, params));
} catch (Exception e) {
    if (currentState.transportRetryCount() >= TRANSPORT_RETRY_BUDGET) {
        throw e;
    }
    var transition = classifyException(currentState.transportRetryCount() + 1, e);
    if (transition == null) throw e;
    advance(currentState, transition, null);
    continue;
}
```

- [ ] **Step 7: 补全 `advance()` TransportRetry case**

将 `advance()` 中的：

```java
case TransitionReason.TransportRetry r  -> { /* s11 extension */ }
```

替换为：

```java
case TransitionReason.TransportRetry r -> {
    System.out.printf("[Recovery] backoff attempt=%d cause=%s%n",
            r.attempt(), r.cause().getMessage());
    try {
        Thread.sleep((long) backoffDelay(r.attempt()));
    } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(ie);
    }
    currentState.incrementTransportRetry();
    currentState.setLastTransition(r);
}
```

- [ ] **Step 8: 运行错误恢复测试，确认全部通过**

```
./mvnw test -pl . -Dtest=QueryEngineErrorRecoveryTest -q
```

期望：4 个测试全部 PASS。

- [ ] **Step 9: 运行全量测试，确认无回归**

```
./mvnw test -pl . -q
```

期望：全部 PASS。

- [ ] **Step 10: 提交**

```bash
git add src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/engine/QueryEngineErrorRecoveryTest.java
git commit -m "feat(s11): add transport retry with classifyException and backoff"
```

---

## 验证检查单

完成后确认：

- [ ] `QueryState.transportRetryCount()` 初始为 0
- [ ] `incrementTransportRetry()` 正确累加
- [ ] 含 `connection`/`timeout` 关键词的 exception 触发重试
- [ ] 预算（3次）耗尽后原始异常向上抛出
- [ ] 不含关键词的 exception 立即抛出，不重试
- [ ] `MAX_TOKENS` 续写路径未受影响
- [ ] `[Recovery] backoff` 日志在重试时打印
- [ ] 全量测试通过，无回归
