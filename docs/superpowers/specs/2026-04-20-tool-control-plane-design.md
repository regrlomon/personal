# s02a Tool Control Plane — Design Spec

**Date:** 2026-04-20  
**Scope:** Refactor `tool` package to introduce `ToolUseContext`, `ToolResultEnvelope`, and `ToolRouter`, aligning the Java implementation with the s02a control-plane model.

---

## Background

s02 established a dispatch map: `tool name → handler`. s02a introduces the next layer: a shared execution context that all handlers can access, a structured result envelope, and a router that separates capability sources (native vs MCP).

This spec implements the **minimal skeleton** variant: stubs for permission/MCP fields are present but empty; no real permission logic or MCP client is wired.

---

## Architecture

Four layers per s02a:

```
1. ToolSpec        — ToolDefinition (unchanged)
2. ToolRouter      — routes by tool name; mcp__ prefix → stub
3. ToolUseContext  — shared execution environment passed to every handler
4. ToolResultEnvelope — structured result: ok / content / isError / attachments
```

---

## New Classes

### `ToolUseContext`

Location: `org.example.agent.tool`

```java
public class ToolUseContext {
    private final Map<String, Object> permissionContext;
    private final Map<String, Object> mcpClients;
    private final Map<String, Object> appState;
    private final List<String> notifications;
    private final String cwd;

    public static ToolUseContext defaults(String cwd) { ... }
}
```

- All fields are immutable after construction.
- `defaults(cwd)` initialises all maps/lists as empty; `cwd` is set to the provided value.
- Getters for all fields; no setters (context is read-only for handlers in this iteration).

### `ToolResultEnvelope`

Location: `org.example.agent.tool`

```java
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

### `ToolRouter`

Location: `org.example.agent.tool`

```java
public class ToolRouter {
    private final ToolRegistry registry;

    public ToolRouter(ToolRegistry registry) { ... }

    public ContentBlock.ToolResult route(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        if (toolUse.name().startsWith("mcp__")) return routeMcp(toolUse, ctx);
        return routeNative(toolUse, ctx);
    }

    private ContentBlock.ToolResult routeNative(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        Tool tool = registry.get(toolUse.name());
        if (tool == null) throw new UnknownToolException(toolUse.name());
        ToolResultEnvelope envelope = tool.execute(toolUse.input(), ctx);
        return new ContentBlock.ToolResult(toolUse.id(), envelope.content());
    }

    private ContentBlock.ToolResult routeMcp(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        throw new UnsupportedOperationException("MCP tools not implemented (s19)");
    }
}
```

---

## Modified Classes

### `Tool` interface

```java
public interface Tool {
    ToolDefinition definition();
    ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx);
}
```

### `ToolRegistry`

- Remove `execute(ContentBlock.ToolUse)`.
- Add `Tool get(String name)` (returns null if not found).
- Keep `register(Tool)` and `definitions()` unchanged.

### Tool implementations (BashTool, ReadFileTool, WriteFileTool, EditFileTool)

- Update signature to `execute(Map<String, Object> input, ToolUseContext ctx)`.
- Wrap success path: `return ToolResultEnvelope.success(result)`.
- Wrap exception path: `catch (Exception e) { return ToolResultEnvelope.error(e.getMessage()); }`.
- `ctx` parameter accepted but unused in this iteration.

### `QueryEngine`

- Constructor signature unchanged: `QueryEngine(ModelClient, ToolRegistry)`.
- Internally constructs `ToolRouter` from the registry.
- `run()` creates `ToolUseContext.defaults(System.getProperty("user.dir"))` once per invocation.
- `collectResults()` calls `toolRouter.route(toolUse, ctx)` instead of `registry.execute(toolUse)`.

---

## Data Flow

```
QueryEngine.run()
  ├── ctx = ToolUseContext.defaults(cwd)
  └── loop:
        collectResults(response)
          └── for each ToolUse block:
                toolRouter.route(toolUse, ctx)
                  ├── [native] registry.get(name).execute(input, ctx)
                  │     └── ToolResultEnvelope → ContentBlock.ToolResult(id, content)
                  └── [mcp__*] UnsupportedOperationException (stub)
```

---

## Test Plan

| File | Change |
|---|---|
| `FileToolsTest`, `BashToolTest` | Add `ToolUseContext.defaults(cwd)` as second argument to `execute()` |
| `ToolRegistryTest` | Remove tests for `registry.execute()`; add test for `registry.get()` |
| `QueryEngineTest` | Constructor signature unchanged; any inline Tool stubs need updated `execute(input, ctx)` signature |
| `ToolRouterTest` (new) | Native dispatch succeeds; `mcp__*` name throws `UnsupportedOperationException`; unknown name throws `UnknownToolException` |

---

## Out of Scope

- Real permission checking
- MCP client wiring (deferred to s19)
- `notifications` mutation by handlers
- `appState` read/write by handlers
- `ToolResultEnvelope.attachments` usage
