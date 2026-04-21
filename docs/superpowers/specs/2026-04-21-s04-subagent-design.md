# s04 Subagent Design

## Goal

Implement the `task` tool so the parent agent can delegate a subtask to a subagent running in a clean, isolated context. The subagent executes independently and returns only its final summary to the parent, keeping the parent's message history free of intermediate noise.

## Approach

Option A: minimal `TaskTool` — one new class, two small modifications to existing classes. No new runner, no new state container. Follows the same constructor-injection pattern as `ReadFileTool` and `TodoTool`.

## Affected Files

| File | Change |
|------|--------|
| `tool/task/TaskTool.java` | New — implements the `task` tool |
| `core/QueryParams.java` | Add `OptionalInt maxTurns` field |
| `engine/QueryEngine.java` | Enforce `maxTurns` at the top of each loop iteration |

## TaskTool

### Constructor

```java
public TaskTool(ModelClient modelClient, ToolRegistry subRegistry, int maxTurns)
```

`modelClient` — shared with the parent agent (stateless, safe to reuse).  
`subRegistry` — tool set available to the subagent; must not contain `TaskTool` (prevents recursion).  
`maxTurns` — hard cap on subagent loop iterations, enforced via `QueryParams`.

### Tool Schema (model-visible)

```json
{
  "name": "task",
  "description": "Run a subtask in a clean context and return a summary.",
  "input_schema": {
    "type": "object",
    "properties": {
      "prompt": { "type": "string" }
    },
    "required": ["prompt"]
  }
}
```

`maxTurns` is a system-level guard, not exposed to the model.

### Execution Logic

1. Extract `prompt` from input.
2. Build fresh messages: `[userMsg(prompt)]`.
3. Construct `QueryParams` with subagent tools and `maxTurns`.
4. Run `new QueryEngine(modelClient, subRegistry).run(params)`.
5. On `QueryResult.Success`: extract last assistant message text and return `ToolResultEnvelope.success(summary)`.
6. On `QueryResult.Failed`: return `ToolResultEnvelope.error(cause.getMessage())`.
7. If success but no assistant message found: return `ToolResultEnvelope.error("subagent produced no output")`.

`isConcurrencySafe()` returns `false` — subagent spawning is stateful.

## QueryParams Change

Add `OptionalInt maxTurns`. Absent means no limit (preserves existing parent-agent behavior).

## QueryEngine Change

At the start of each loop iteration, check:

```java
if (params.maxTurns().isPresent() && state.turnCount() >= params.maxTurns().getAsInt()) {
    return buildSuccessFromCurrentState(state);
}
```

Returns whatever the subagent has produced so far rather than an error, since reaching the turn limit is an expected soft stop.

## Registration Pattern

```java
var subRegistry = new ToolRegistry();
subRegistry.register(new ReadFileTool(sandbox));
subRegistry.register(new BashTool());
// TaskTool intentionally absent — prevents infinite recursion

var parentRegistry = new ToolRegistry();
parentRegistry.register(new TodoTool());
parentRegistry.register(new TaskTool(modelClient, subRegistry, 10));
```

## Recursion Prevention

Handled entirely at registration time. `subRegistry` has no `TaskTool`, so the model cannot call `task` from within a subagent. No runtime depth tracking needed at this stage.

## Future: Fork Mode

When needed, add `List<Message> conversationHistory` to `ToolUseContext`. `QueryEngine` maintains it via context modifier each turn. `TaskTool` reads it when `fork=true` is added to the tool schema. No existing logic changes — additive only.
