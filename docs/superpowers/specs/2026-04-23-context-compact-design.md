# Context Compact (s06) — Design Spec

**Date:** 2026-04-23  
**Scope:** Implement 3-layer context compaction in the Java agent framework per `s06-context-compact.md`.

---

## Problem

As the agent loop runs more turns, the message list grows unboundedly:

- Large tool outputs inflate context immediately
- Old tool results accumulate across turns
- Eventually MAX_TOKENS is hit and the task interrupts

Without compaction, long sessions become expensive and unreliable.

---

## Goals

1. Prevent context from growing without bound across a session
2. Preserve enough continuity that the agent can keep working after compaction
3. Reuse the same compact logic for both automatic (MAX_TOKENS) and manual (`compact` tool) triggers

---

## Non-Goals

- Proactive size estimation (byte/token count threshold) — only reactive on MAX_TOKENS
- LLM-generated summaries — heuristic extraction is sufficient for the teaching version
- Cross-session memory — that is s09's concern

---

## Architecture

### New Files

```
src/main/java/org/example/agent/
  engine/ContextCompactor.java     ← 3-layer compact core
  tool/CompactTool.java            ← agent-callable compact tool
```

### Modified Files

| File | Change |
|------|--------|
| `engine/QueryEngine.java` | Wire compactor into loop, `decide()`, `advance()`, `buildToolResultMessage()` |
| `core/QueryState.java` | Add `replaceMessages(List<Message>)` |

No new packages. No new interfaces.

---

## Layer 1 — Persist Large Output

**Where:** `QueryEngine.buildToolResultMessage()`, applied to each `ToolResult` before adding to the message.

**Threshold:** `PERSIST_THRESHOLD = 10_000` characters.

**Behavior:**
- If `content.length() <= threshold` → pass through unchanged
- Otherwise → write full content to `.task_outputs/tool-results/<toolUseId>.txt` and return marker:

```
<persisted-output>
Full output saved to: .task_outputs/tool-results/<id>.txt
Preview:
...(first 2000 chars)...
</persisted-output>
```

**Effect:** The model sees enough context to continue reasoning, but the raw large output is never stored permanently in the message list.

---

## Layer 2 — Micro-Compact

**Where:** Top of `QueryEngine.run()` while loop, before every model call.

**Behavior:**
- Collect all user messages that contain `ToolResult` blocks
- Keep the **most recent 3** intact
- Replace `ToolResult.content` in older ones with `"[Earlier tool result omitted for brevity]"`
- Return a new list (immutable transform, never mutates in-place)

**Constant:** `MICRO_KEEP_RECENT = 3`

---

## Layer 3 — Full Compact

**Trigger:** `decide()` returns `CompactRetry` when `stopReason == MAX_TOKENS && !state.hasAttemptedCompact()`. If MAX_TOKENS fires again after a compact has already been attempted, falls back to `MaxTokensRecovery` (existing behavior).

**Summary strategy — heuristic template extraction:**

```
## Compacted Context

### Current Goal
<content of the first user message>

### Completed Actions
<PlanItems with status COMPLETED, one per line>

### Pending Tasks
<PlanItems with status PENDING or IN_PROGRESS, one per line>

### Persisted Files
<file paths extracted from <persisted-output> markers in any message>

### Recent Assistant Output
<first 1000 chars of the last assistant Text block>
```

Wrapped as:
```
"This conversation was compacted for continuity.\n\n" + summary
```

Returned as a single `Message.user(...)` that replaces the entire message list.

**After compact:**
- `state.replaceMessages(compacted)` — swap message list
- `state.markCompactAttempted()` — guard against re-compact loop
- Do NOT `incrementTurn()` — retry the model call immediately in the next iteration

---

## ContextCompactor Class

```java
public class ContextCompactor {

    private static final int PERSIST_THRESHOLD = 10_000;
    private static final int MICRO_KEEP_RECENT  = 3;
    private static final String PLACEHOLDER =
            "[Earlier tool result omitted for brevity]";

    private final Path outputDir;

    public ContextCompactor(Path outputDir) { ... }

    /** Layer 1 */
    public String persistIfLarge(String toolUseId, String content) { ... }

    /** Layer 2 */
    public List<Message> microCompact(List<Message> messages) { ... }

    /** Layer 3 */
    public List<Message> fullCompact(List<Message> messages, PlanningState plan) { ... }
}
```

`outputDir` defaults to `Paths.get(cwd, ".task_outputs", "tool-results")`, created lazily on first write.

---

## QueryEngine Changes

### Constructor

Add an overload that accepts an explicit `ContextCompactor` (for testing). Existing constructors create a default compactor using `System.getProperty("user.dir")`.

### `run()` loop

```java
while (true) {
    // Layer 2: micro-compact before every model call
    state.replaceMessages(compactor.microCompact(state.messages()));

    if (params.maxTurns() != null && state.turnCount() > params.maxTurns()) { ... }

    var response = modelClient.call(buildRequest(state, params));
    // ... existing tool-use handling
}
```

### `buildToolResultMessage()`

```java
// Apply Layer 1 to each tool result
for (var r : results) {
    var content = compactor.persistIfLarge(r.toolUseId(), r.content());
    blocks.add(new ContentBlock.ToolResult(r.toolUseId(), content));
}
```

### `decide()`

```java
case MAX_TOKENS -> state.hasAttemptedCompact()
    ? new TransitionReason.MaxTokensRecovery(state.continuationCount() + 1)
    : new TransitionReason.CompactRetry();
```

### `advance(CompactRetry)`

```java
case TransitionReason.CompactRetry c -> {
    var compacted = compactor.fullCompact(state.messages(), ctx.planningState());
    state.replaceMessages(compacted);
    state.markCompactAttempted();
    state.setLastTransition(c);
    // no incrementTurn — retry model call immediately
}
```

---

## QueryState Change

```java
public void replaceMessages(List<Message> newMessages) {
    messages.clear();
    messages.addAll(newMessages);
}
```

---

## CompactTool

```java
public class CompactTool implements Tool {

    // isConcurrencySafe() = false

    private final ContextCompactor compactor;
    private final Supplier<List<Message>> messagesReader;
    private final Consumer<List<Message>> messagesWriter;
    private final Supplier<PlanningState> planReader;

    public CompactTool(
        ContextCompactor compactor,
        Supplier<List<Message>> messagesReader,
        Consumer<List<Message>> messagesWriter,
        Supplier<PlanningState> planReader
    ) { ... }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var compacted = compactor.fullCompact(messagesReader.get(), planReader.get());
        messagesWriter.accept(compacted);
        return ToolResultEnvelope.ok("Context compacted successfully.");
    }
}
```

`QueryEngine` holds two `AtomicReference` fields so `CompactTool` can be wired at construction time and still read live per-run state:

```java
// QueryEngine fields
private final AtomicReference<QueryState>     stateRef = new AtomicReference<>();
private final AtomicReference<ToolUseContext> ctxRef   = new AtomicReference<>();
```

`run()` initialises them before the loop:
```java
var state = QueryState.from(params);
stateRef.set(state);
ctxRef.set(ToolUseContext.defaults(cwd));
// in loop after tool execution: ctxRef.set(execResult.updatedContext())
```

`CompactTool` is constructed once in the `QueryEngine` constructor using lambdas that dereference the refs at call time:
```java
new CompactTool(
    compactor,
    () -> List.copyOf(stateRef.get().messages()),
    msgs -> stateRef.get().replaceMessages(msgs),
    () -> ctxRef.get().planningState()
)
```

This keeps tool registration outside the loop while always reading the current `state` and `ctx`.

---

## Testing Plan

| Test class | What it covers |
|-----------|----------------|
| `ContextCompactorTest` | Layer 1: threshold boundary, file write, marker format |
| `ContextCompactorTest` | Layer 2: keeps recent 3, replaces older, leaves non-tool messages untouched |
| `ContextCompactorTest` | Layer 3: summary sections populated correctly from messages + plan |
| `QueryEngineCompactTest` | MAX_TOKENS → CompactRetry → retry (no incrementTurn) |
| `QueryEngineCompactTest` | MAX_TOKENS after compact → MaxTokensRecovery |
| `CompactToolTest` | Manual compact rewrites messages via consumer |

All tests use `FakeModelClient` (existing pattern), no real I/O except Layer 1 which uses `@TempDir`.

---

## File Layout After Implementation

```
src/main/java/org/example/agent/
  core/
    QueryState.java              (+ replaceMessages)
  engine/
    ContextCompactor.java        (NEW)
    QueryEngine.java             (modified)
  tool/
    CompactTool.java             (NEW)

src/test/java/org/example/agent/
  engine/
    ContextCompactorTest.java    (NEW)
    QueryEngineCompactTest.java  (NEW)
  tool/
    CompactToolTest.java         (NEW)
```
