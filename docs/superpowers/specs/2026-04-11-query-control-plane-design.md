# Design: Java Query Control Plane Framework

**Date:** 2026-04-11  
**Reference:** `src/main/resources/zh/s00a-query-control-plane.md`  
**Java version:** 21  
**Build:** Maven

---

## Goal

Build the foundational Java framework skeleton for the agent query control plane. This is not a demo — it is the extensible base that all future chapter implementations (s01, s06, s08, s11, etc.) will build on.

---

## Package Structure

```
org.example.agent
├── core/       Shared value objects + state container
│               Message, ContentBlock, Role, ToolDefinition
│               QueryParams, QueryState, TransitionReason
├── model/      Model client abstraction
│               ModelClient (interface), ModelRequest, ModelResponse, StopReason
├── tool/       Tool abstraction
│               Tool (interface), ToolRegistry, UnknownToolException
└── engine/     Main loop driver
│               QueryEngine, QueryResult
```

**Dependency direction (one-way, no cycles):**

```
engine  →  core
engine  →  model
engine  →  tool
model   →  core      (ModelRequest uses Message, ToolDefinition; ModelResponse uses ContentBlock)
tool    →  core      (Tool uses ToolDefinition; ToolRegistry uses ContentBlock.ToolResult)
core    →  (none)
```

`core` has zero dependencies. `engine` is the only package that knows the full picture.

---

## `core` Package

### Message + ContentBlock + Role

Fundamental message types shared across all layers.

```java
public record Message(Role role, List<ContentBlock> content) {
    public static Message user(String text) { ... }
    public static Message assistant(String text) { ... }
}

public enum Role { USER, ASSISTANT }

public sealed interface ContentBlock permits
    ContentBlock.Text,
    ContentBlock.ToolUse,
    ContentBlock.ToolResult {

    record Text(String text)                                         implements ContentBlock {}
    record ToolUse(String id, String name, Map<String,Object> input) implements ContentBlock {}
    record ToolResult(String toolUseId, String content)              implements ContentBlock {}
}
```

### ToolDefinition

Describes a tool's signature for passing to the model. Lives in `core` so both `model` and `tool` can reference it without a cycle.

```java
public record ToolDefinition(
    String              name,
    String              description,
    Map<String, Object> inputSchema   // JSON Schema map
) {}
```

### QueryParams (record)

External, immutable input handed to the engine at query start. Not mutated during the loop.

```java
public record QueryParams(
    List<Message>     messages,
    String            systemPrompt,
    @Nullable String  fallbackModel,
    @Nullable Integer maxOutputTokensOverride,
    @Nullable Integer maxTurns
) {}
```

### QueryState

Mutable container that evolves across loop iterations. Fields are private; mutation is exposed through named methods so callers stay readable and the intent of each change is explicit.

```java
public class QueryState {
    private final List<Message> messages;
    private int                 turnCount           = 1;
    private int                 continuationCount   = 0;
    private boolean             hasAttemptedCompact = false;
    private boolean             stopHookActive      = false;
    private Integer             maxOutputTokensOverride;
    private TransitionReason    lastTransition;

    public static QueryState from(QueryParams params) { ... }

    // Read accessors
    public List<Message>       messages()               { ... }
    public int                 turnCount()              { ... }
    public int                 continuationCount()      { ... }
    public boolean             hasAttemptedCompact()    { ... }
    public boolean             stopHookActive()         { ... }
    public Optional<Integer>   maxOutputTokensOverride(){ ... }
    public Optional<TransitionReason> lastTransition()  { ... }

    // Mutation methods (called by QueryEngine to advance state)
    public void appendMessage(Message m)                { ... }
    public void incrementTurn()                         { ... }
    public void incrementContinuation()                 { ... }
    public void markCompactAttempted()                  { ... }
    public void setStopHookActive(boolean active)       { ... }
    public void setMaxOutputTokensOverride(Integer v)   { ... }
    public void setLastTransition(TransitionReason t)   { ... }
}
```

### TransitionReason (sealed interface)

Each subtype is a record. `switch` on `TransitionReason` is exhaustively checked by the compiler — adding a new subtype forces every switch site to handle it.

```java
public sealed interface TransitionReason permits
    TransitionReason.ToolResultContinuation,
    TransitionReason.MaxTokensRecovery,
    TransitionReason.CompactRetry,
    TransitionReason.TransportRetry,
    TransitionReason.StopHookContinuation,
    TransitionReason.BudgetContinuation {

    record ToolResultContinuation(List<ContentBlock.ToolResult> results) implements TransitionReason {}
    record MaxTokensRecovery(int attempt)                                implements TransitionReason {}
    record CompactRetry()                                                implements TransitionReason {}
    record TransportRetry(int attempt, Throwable cause)                  implements TransitionReason {}
    record StopHookContinuation(String hookName)                         implements TransitionReason {}
    record BudgetContinuation()                                          implements TransitionReason {}
}
```

---

## `model` Package

### ModelRequest / ModelResponse / StopReason

```java
public record ModelRequest(
    List<Message>        messages,
    String               systemPrompt,
    List<ToolDefinition> tools,
    @Nullable Integer    maxOutputTokens
) {}

public record ModelResponse(
    List<ContentBlock> content,
    StopReason         stopReason,
    int                inputTokens,
    int                outputTokens
) {}

public enum StopReason { END_TURN, TOOL_USE, MAX_TOKENS, STOP_SEQUENCE }
```

### ModelClient (interface)

The only seam between framework and actual API implementation. Concrete implementations are injected; the framework never instantiates one directly.

```java
public interface ModelClient {
    ModelResponse call(ModelRequest request);
}
```

---

## `tool` Package

### Tool (interface)

```java
public interface Tool {
    ToolDefinition definition();
    String execute(Map<String, Object> input);
}
```

### ToolRegistry

```java
public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) { ... }
    public List<ToolDefinition> definitions() { ... }

    // Executes a ToolUse block returned by the model; returns a ToolResult block
    public ContentBlock.ToolResult execute(ContentBlock.ToolUse toolUse) { ... }
}
```

`UnknownToolException extends RuntimeException` — thrown when the model calls an unregistered tool. The `engine` error-recovery branch (s11 extension point) catches this.

---

## `engine` Package

### QueryResult (sealed interface)

```java
public sealed interface QueryResult permits
    QueryResult.Success,
    QueryResult.Failed {

    record Success(List<Message> messages, int totalTurns) implements QueryResult {}
    record Failed(Throwable cause, List<Message> messages)  implements QueryResult {}
}
```

### QueryEngine

The main loop. Two private methods enforce a single-responsibility split:

- `decide()` — reads response, returns a `TransitionReason` or `null` (terminal); never mutates state
- `advance()` — mutates `QueryState` via its named methods; never calls the model; compiler enforces exhaustive branch coverage

```java
public class QueryEngine {
    private final ModelClient  modelClient;
    private final ToolRegistry toolRegistry;

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) { ... }

    public QueryResult run(QueryParams params) {
        var state = QueryState.from(params);
        while (true) {
            var request = buildRequest(state, params);
            var response = modelClient.call(request);

            var transition = decide(state, response);
            if (transition == null) {
                return new QueryResult.Success(state.messages(), state.turnCount());
            }
            advance(state, transition, response);
        }
    }

    private TransitionReason decide(QueryState state, ModelResponse response) {
        return switch (response.stopReason()) {
            case END_TURN      -> null;
            case TOOL_USE      -> new TransitionReason.ToolResultContinuation(collectResults(response));
            case MAX_TOKENS    -> new TransitionReason.MaxTokensRecovery(state.continuationCount() + 1);
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
            case TransitionReason.CompactRetry        c -> { /* s06 extension */ }
            case TransitionReason.TransportRetry      r -> { /* s11 extension */ }
            case TransitionReason.StopHookContinuation h -> { /* s08 extension */ }
            case TransitionReason.BudgetContinuation   b -> { /* budget extension */ }
        }
    }

    private static final String CONTINUE_PROMPT = "Please continue.";
}
```

---

## Extension Points

| Future chapter | Where to extend |
|----------------|-----------------|
| s01 full loop  | `QueryEngine.run()` — add turn limit check against `params.maxTurns()` |
| s06 compact    | `TransitionReason.CompactRetry` branch in `advance()` |
| s08 hooks      | `TransitionReason.StopHookContinuation` branch in `advance()` |
| s11 error recovery | `TransitionReason.TransportRetry` branch + wrap `modelClient.call()` in try/catch |
| Real ModelClient | Implement `ModelClient` interface; inject via `QueryEngine` constructor |

---

## Out of Scope

- Actual HTTP calls to Claude API (ModelClient is an interface only)
- Context compaction logic (s06)
- Hook execution (s08)
- Retry backoff logic (s11)
- Concurrency / thread safety
