# s11 Error Recovery — Design Spec

**Date:** 2026-04-28  
**Branch:** master  
**Scope:** `QueryEngine` + `QueryState`，不新增类

---

## 目标

把主循环从"报错就崩"升级为"先判断错误类型，再选择恢复路径"。  
s11 聚焦三条最小恢复路径：

| 场景 | 已有支持 | s11 新增 |
|------|---------|---------|
| 输出截断续写 (`MAX_TOKENS`) | `MaxTokensRecovery` + `advance()` ✓ | — |
| 上下文过长压缩重试 | `CompactRetry` + `advance()` ✓ | — |
| transport 抖动退避重试 | `TransportRetry` record 预定义 ✓ | `classifyException()` + `advance()` 实现 |

---

## 数据结构变更

### `QueryState` 新增字段

```java
private int transportRetryCount = 0;
```

读写方法（与 `continuationCount` 完全对称）：

```java
public int transportRetryCount() { return transportRetryCount; }
public void incrementTransportRetry() { transportRetryCount++; }
```

### `TransitionReason` 无需改动

`TransportRetry(int attempt, Throwable cause)` 已预定义，直接使用。

---

## 错误分类：`classifyException()`

在 `QueryEngine` 新增私有静态方法，镜像 `decide()` 的"检测 → 返回决策"模式：

```java
private static final int TRANSPORT_RETRY_BUDGET = 3;

private static final List<String> TRANSIENT_KEYWORDS =
        List.of("timeout", "rate", "unavailable", "connection");

private static TransitionReason classifyException(int attempt, Throwable e) {
    var msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
    if (TRANSIENT_KEYWORDS.stream().anyMatch(msg::contains)) {
        return new TransitionReason.TransportRetry(attempt, e);
    }
    return null; // 不可恢复 → 调用方 rethrow
}
```

- 返回 `null` 表示不可恢复，`run()` 收到 null 直接 rethrow 原始异常
- `TRANSPORT_RETRY_BUDGET` 的预算判断在 `run()` 里前置，`classifyException` 职责单一

---

## 主循环修改：`run()`

`modelClient.call()` 用 try-catch 包裹：

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

- 预算判断前置，防止超限后仍进入 `advance()`
- `advance(..., null)` 传 null response —— `TransportRetry` case 不需要 response
- try 块之后的 `response.stopReason()` 判断逻辑不变

---

## `advance()` 补全 TransportRetry case

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
    // 不 incrementTurn — 透明重试不计轮次
}
```

退避计算（指数退避，上限 30 秒）：

```java
private static double backoffDelay(int attempt) {
    return Math.min(1000.0 * Math.pow(2, attempt - 1), 30_000.0);
}
```

| attempt | delay |
|---------|-------|
| 1 | 1 秒 |
| 2 | 2 秒 |
| 3 | 4 秒 |
| 4+ | 30 秒（上限） |

---

## 日志输出

恢复路径必须有日志（教学可见性要求）：

```
[Recovery] backoff attempt=1 cause=connection reset
[Recovery] backoff attempt=2 cause=connection reset
```

---

## 测试策略

| # | 场景 | 断言 |
|---|------|------|
| 1 | 续写未被破坏：`MAX_TOKENS` → `END_TURN` | messages 含 "Please continue."，正常返回 |
| 2 | transport 成功重试：第 1 次抛 `"connection reset"`，第 2 次正常 | `QueryResult.Success`，`transportRetryCount == 1` |
| 3 | 预算耗尽：连续 4 次 transient exception | `run()` 向上抛出原始异常 |
| 4 | 不可恢复异常：`"internal server error"`（无关键词） | 立即抛出，`transportRetryCount == 0` |

---

## 改动文件清单

| 文件 | 改动类型 |
|------|---------|
| `core/QueryState.java` | 新增 `transportRetryCount` 字段 + 读写方法 |
| `engine/QueryEngine.java` | 新增常量、`classifyException()`、`backoffDelay()`；修改 `run()` 加 try-catch；补全 `advance()` TransportRetry case |

---

## 边界说明

- 不引入随机抖动（教学阶段简化）
- 不覆盖 `StopHookContinuation` / `BudgetContinuation`（分属 s08 / budget 章节）
- transport 重试不 `incrementTurn()`，和 `CompactRetry` 保持一致
