# Tool Execution Runtime Design (s02b)

**Date:** 2026-04-21  
**Reference:** `src/main/resources/zh/s02b-tool-execution-runtime.md`

## Goal

Upgrade `QueryEngine.collectResults()` from a synchronous sequential dispatch to a proper execution runtime that:
- Partitions tool calls into concurrent-safe and exclusive batches
- Executes safe batches in parallel via `CompletableFuture`
- Collects results in original (not completion) order
- Applies context modifiers in original order after each batch

## What Already Exists (s02a)

- `Tool` interface with `execute(input, ctx) → ToolResultEnvelope`
- `ToolRouter` dispatches to native handlers or MCP stub
- `ToolUseContext` immutable record (cwd, permissions, mcpClients, appState, notifications)
- `ToolResultEnvelope` record (ok, content, isError, attachments)
- `QueryEngine.collectResults()` maps tool uses sequentially through `ToolRouter`

## New / Changed Classes

| Class | Action | Summary |
|---|---|---|
| `Tool` | modify | Add `default boolean isConcurrencySafe() { return false; }` |
| `ReadFileTool` | modify | Override `isConcurrencySafe()` to return `true` |
| `ToolResultEnvelope` | modify | Add `Optional<UnaryOperator<ToolUseContext>> contextModifier` field |
| `ToolExecutionBatch` | new | Record grouping `List<ToolUse>` + `boolean concurrencySafe` |
| `TrackedTool` | new | Record tracking id, name, `Status` enum, result |
| `ToolExecutionRuntime` | new | Core class: partition, execute, collect, apply modifiers |
| `QueryEngine` | modify | `collectResults()` delegates to `ToolExecutionRuntime`; ctx passed explicitly through loop |

## Data Structures

### `Tool` interface

```java
default boolean isConcurrencySafe() { return false; }
```

Only `ReadFileTool` overrides this to `true`. `BashTool`, `WriteFileTool`, `EditFileTool` keep the default.

### `ToolResultEnvelope`

Add field:
```java
Optional<UnaryOperator<ToolUseContext>> contextModifier
```

Existing `success(String)` and `error(String)` factories fill this with `Optional.empty()`. No breaking changes to existing call sites.

### `ToolExecutionBatch`

```java
record ToolExecutionBatch(
    List<ContentBlock.ToolUse> toolUses,
    boolean concurrencySafe
) {}
```

### `TrackedTool`

```java
record TrackedTool(
    String id,
    String name,
    Status status,
    ToolResultEnvelope result
) {
    enum Status { QUEUED, EXECUTING, COMPLETED }
}
```

### `ExecutionResult`

```java
record ExecutionResult(
    List<ContentBlock.ToolResult> toolResults,
    ToolUseContext updatedContext
) {}
```

### `ToolExecutionRuntime`

```java
class ToolExecutionRuntime {
    ToolExecutionRuntime(ToolRouter router, ExecutorService executor);

    ExecutionResult execute(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx);
    List<ToolExecutionBatch> partition(List<ContentBlock.ToolUse> toolUses);
}
```

## Partitioning Rules

Scan tool use list left to right:
- Consecutive concurrency-safe tools → one `ToolExecutionBatch(concurrencySafe=true)`
- Each non-safe tool → its own `ToolExecutionBatch(concurrencySafe=false)`

Example:
```
[read, read, write, read]
→ [safe(read, read)], [exclusive(write)], [safe(read)]
```

## Execution Flow Inside `ToolExecutionRuntime.execute()`

```
for each batch:
  if concurrencySafe:
    submit each tool to executor via CompletableFuture.supplyAsync
    CompletableFuture.allOf(...).join()
    collect results in original index order (futures.get(i).join())
  else:
    execute each tool serially

  for each envelope in original order:
    if envelope.contextModifier().isPresent():
      ctx = envelope.contextModifier().get().apply(ctx)

return ExecutionResult(toolResults, ctx)
```

## `QueryEngine` Changes

`collectResults()` becomes a thin delegator:

```java
private List<ContentBlock.ToolResult> collectResults(
    List<ContentBlock.ToolUse> toolUses,
    ToolUseContext ctx,
    // returns updated ctx via out-param pattern replaced by:
) { ... }
```

Because `collectResults` needs to return both results and updated ctx, the loop in `run()` is restructured to pass ctx explicitly:

```java
// Inside run() loop:
ExecutionResult execResult = runtime.execute(toolUses, ctx);
ctx = execResult.updatedContext();
List<ContentBlock.ToolResult> toolResults = execResult.toolResults();
```

`ctx` is a local variable in `run()`, updated each iteration. `QueryEngine` holds no mutable state.

## ExecutorService

- `ToolExecutionRuntime` accepts an injected `ExecutorService`
- Tests pass `Executors.newFixedThreadPool(4)` (or a direct executor for sync testing)
- Production default: `ForkJoinPool.commonPool()`

## Error Handling

Unchanged from s02a: `UnknownToolException` and `UnsupportedOperationException` are caught in `ToolExecutionRuntime` when collecting `CompletableFuture` results (via `exceptionally` or try-catch around `join()`), and converted to error `ToolResultEnvelope`. Other tools in the same concurrent batch still complete normally.

## Testing Notes

- `ToolExecutionRuntimeTest`: partition logic, safe batch runs concurrently (verify via thread names or latency), exclusive batch runs serially, results always in original order, context modifiers applied in order
- `QueryEngineTest`: existing tests should pass unchanged; add one test with mixed safe/unsafe tools
- `ToolResultEnvelopeTest`: add test for `contextModifier` field presence

## Out of Scope

- Progress messages (streaming partial results mid-tool) — deferred
- Tool cancellation / timeout — deferred
- Permission enforcement via `permissionContext` — deferred to s07
- MCP tool execution — deferred to s19
