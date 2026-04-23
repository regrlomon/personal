# Hook System 设计文档 (s08)

**日期**: 2026-04-23  
**状态**: 待实现  
**参考**: `src/main/resources/zh/s08-hook-system.md`

---

## 目标

在不修改主循环代码的前提下，在固定时机插入额外行为。引入三层抽象（HookEvent / HookResult / HookRunner），支持三个事件（SessionStart / PreToolUse / PostToolUse），通过 shell 命令脚本实现可配置的扩展点。

---

## 架构总览

```
QueryEngine
  │
  ├── SessionStart hook（循环开始前）
  │
  └── ToolExecutionRuntime.routeSafely()
        ├── PreToolUse hook（工具执行前）
        │     exit_code 1 → blocked result，跳过工具执行
        │     exit_code 2 → 记录 injectionMessage，继续执行
        ├── router.routeToEnvelope()（实际工具执行）
        └── PostToolUse hook（工具执行后）
              exit_code 2 → 记录 injectionMessage

ExecutionResult 携带 injectionMessages → QueryEngine 追加为 user message
```

HookRunner 通过 ToolUseContext 传递（与 s07 PermissionChecker 完全对称）。

---

## 新增包：`org.example.agent.hook`

### HookEventName（enum）

```java
public enum HookEventName {
    SESSION_START, PRE_TOOL_USE, POST_TOOL_USE
}
```

### HookEvent（record）

```java
public record HookEvent(HookEventName name, Map<String, Object> payload) {}
```

### HookResult（record）

```java
public record HookResult(int exitCode, String message) {
    public static HookResult ok()                    { return new HookResult(0, ""); }
    public static HookResult block(String message)   { return new HookResult(1, message); }
    public static HookResult inject(String message)  { return new HookResult(2, message); }
}
```

exit_code 语义：
- `0` — 正常继续
- `1` — 阻止当前动作（仅 PreToolUse 有效）
- `2` — 注入一条 user message，再继续

### HookRunner（接口）

```java
public interface HookRunner {
    HookResult run(HookEvent event);
}
```

`HookEvent` 在调用处构造：`new HookEvent(HookEventName.PRE_TOOL_USE, payload)`。

### HookConfig（配置加载）

从 `hooks.json` 加载，格式：

```json
{
  "SessionStart": ["./scripts/on-start.sh"],
  "PreToolUse":   ["./scripts/pre-guard.sh"],
  "PostToolUse":  ["./scripts/post-log.sh"]
}
```

加载路径优先级：
1. 构造时显式传入路径
2. 工作目录下 `.claude/hooks.json`
3. 文件不存在 → 空配置（无任何 hook）

### ShellHookRunner（实现）

执行协议：
1. 序列化 payload 为 JSON，写入子进程 stdin
2. 等待进程退出，超时 10s 视为 exit_code 0 并打印 warn
3. 读取 stdout 作为 message
4. exit_code 1 或 2 → 立即短路，不执行后续命令
5. 全部命令通过 → 返回 `HookResult.ok()`

HookRunner 为 null（未配置）时，所有事件直接视为 `HookResult.ok()`。

---

## 修改现有类

### ToolUseContext

新增字段与工厂方法（与 PermissionChecker 对称）：

```java
private final HookRunner hookRunner;

public ToolUseContext withHookRunner(HookRunner hookRunner) { ... }
public HookRunner hookRunner() { return hookRunner; }
```

### ToolExecutionRuntime

`routeSafely()` 改造为接收一个可变的 `injectionMessages` 收集器（由 `execute()` 创建并传入），内部 append 注入消息：

```
execute(toolUses, ctx):
    injectionMessages = new ArrayList<>()
    ...
    for each batch → routeSafely(toolUse, ctx, injectionMessages)
    return ExecutionResult(allResults, currentCtx, injectionMessages)

routeSafely(toolUse, ctx, injectionMessages):
    pre = hookRunner != null
        ? hookRunner.run(new HookEvent(PRE_TOOL_USE, {tool_name, input}))
        : HookResult.ok()

    if pre.exitCode == 1 → return ToolResultEnvelope.error(pre.message)

    if pre.exitCode == 2 → injectionMessages.add(pre.message)

    result = router.routeToEnvelope(toolUse, ctx)

    post = hookRunner != null
        ? hookRunner.run(new HookEvent(POST_TOOL_USE, {tool_name, input, output}))
        : HookResult.ok()

    if post.exitCode == 2 → injectionMessages.add(post.message)

    return result
```

注意：`hookRunner` 从 `ctx.hookRunner()` 取得，`routeSafely()` 签名不变，只增加 `injectionMessages` 参数。

### ExecutionResult

```java
public record ExecutionResult(
    List<ContentBlock.ToolResult> toolResults,
    ToolUseContext updatedContext,
    List<String> injectionMessages   // 新增
) {}
```

### QueryEngine

```java
// 1. 新增字段
private final HookRunner hookRunner;

// 2. 构造时注入（新增 withHookRunner 重载）
public QueryEngine(ModelClient, ToolRegistry, HookRunner) { ... }

// 3. run() 循环前
if (hookRunner != null) {
    hookRunner.run(new HookEvent(SESSION_START, Map.of(
        "system_prompt", params.systemPrompt()
    )));
}

// 4. 每轮工具执行后
for (String msg : execResult.injectionMessages()) {
    currentState.appendMessage(Message.user(msg));
}

// 5. currentCtx 构建时携带 hookRunner
currentCtx = baseCtx.withHookRunner(hookRunner);
```

---

## 测试策略

| 测试类 | 位置 | 覆盖点 |
|---|---|---|
| `HookResultTest` | `hook/` | exitCode 0/1/2 语义，静态工厂方法 |
| `HookConfigTest` | `hook/` | hooks.json 加载、空文件容忍、未知事件名忽略 |
| `ShellHookRunnerTest` | `hook/` | 真实子进程：exit 0/1/2 各分支；超时处理；多命令短路 |
| `QueryEngineHookTest` | `engine/` | SessionStart 被调用；exit_code 2 注入消息出现在消息列表中 |
| `ToolExecutionRuntimeHookTest` | `tool/` | PreToolUse exit_code 1 → 工具不执行；exit_code 2 → injectionMessages 非空 |

`QueryEngineHookTest` 和 `ToolExecutionRuntimeHookTest` 中，HookRunner 用 stub（实现接口的 lambda），不真正 fork 进程。

---

## 教学边界

s08 只实现三个事件。未来可扩展方向（不在本章范围内）：
- `PostSession`、`PreCompact` 等生命周期事件
- `HookConfig` 热重载（`reload()` 方法）
- 多 HookRunner 组合（CompositeHookRunner）
