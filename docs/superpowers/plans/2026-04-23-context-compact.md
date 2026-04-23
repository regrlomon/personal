# Context Compact (s06) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement 3-layer context compaction so the agent loop can handle long sessions without hitting MAX_TOKENS hard failures.

**Architecture:** `ContextCompactor` (new, in `engine/`) encapsulates the 3 layers: persist large tool outputs to disk, replace old tool results with a placeholder, and summarize the full history into a single user message. `CompactTool` (new, in `tool/`) exposes the full-compact operation as a callable agent tool. `QueryEngine` wires both in: micro-compact runs at the top of every loop, full-compact triggers on `MAX_TOKENS` via the existing `CompactRetry` transition stub.

**Tech Stack:** Java 21, JUnit 5, standard library only (`java.nio.file`, `java.util`). No new dependencies.

---

## File Map

| File | Status | Responsibility |
|------|--------|----------------|
| `src/main/java/org/example/agent/core/QueryState.java` | Modify | Add `replaceMessages()` |
| `src/main/java/org/example/agent/engine/ContextCompactor.java` | Create | 3-layer compact logic |
| `src/main/java/org/example/agent/engine/QueryEngine.java` | Modify | Wire compactor, instance fields, `decide()`, `advance()` |
| `src/main/java/org/example/agent/tool/CompactTool.java` | Create | Agent-callable compact tool |
| `src/test/java/org/example/agent/core/QueryStateTest.java` | Modify | Add `replaceMessages` test |
| `src/test/java/org/example/agent/engine/ContextCompactorTest.java` | Create | Unit tests for all 3 layers |
| `src/test/java/org/example/agent/engine/QueryEngineCompactTest.java` | Create | Integration tests for compact flow |
| `src/test/java/org/example/agent/tool/CompactToolTest.java` | Create | Unit tests for CompactTool |
| `src/test/java/org/example/agent/engine/QueryEngineTest.java` | Modify | Update existing MAX_TOKENS test for new behavior |

---

## Task 1: Add `replaceMessages` to `QueryState`

**Files:**
- Modify: `src/main/java/org/example/agent/core/QueryState.java`
- Modify: `src/test/java/org/example/agent/core/QueryStateTest.java`

- [ ] **Step 1: Write the failing tests**

Add these two tests to `QueryStateTest.java` inside the class:

```java
@Test
void replace_messages_swaps_list() {
    var state = QueryState.from(minimalParams());
    var newMessages = List.of(Message.user("compacted"));

    state.replaceMessages(newMessages);

    assertEquals(1, state.messages().size());
    assertEquals("compacted",
            ((ContentBlock.Text) state.messages().get(0).content().get(0)).text());
}

@Test
void replace_messages_result_is_still_unmodifiable() {
    var state = QueryState.from(minimalParams());
    state.replaceMessages(List.of(Message.user("new")));
    assertThrows(UnsupportedOperationException.class,
            () -> state.messages().add(Message.user("hack")));
}
```

Add the missing import at the top of the file:
```java
import org.example.agent.core.ContentBlock;
```

- [ ] **Step 2: Run to confirm they fail**

Run: `mvn test -Dtest=QueryStateTest` (from Windows terminal with Maven on PATH, or via IntelliJ)

Expected: compile error — `replaceMessages` does not exist yet.

- [ ] **Step 3: Add `replaceMessages` to `QueryState`**

In `QueryState.java`, add after the `markCompactAttempted()` method (line ~73):

```java
public void replaceMessages(List<Message> newMessages) {
    messages.clear();
    messages.addAll(newMessages);
}
```

- [ ] **Step 4: Run tests to confirm they pass**

Run: `mvn test -Dtest=QueryStateTest`

Expected: all 9 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/core/QueryState.java \
        src/test/java/org/example/agent/core/QueryStateTest.java
git commit -m "feat(core): add replaceMessages to QueryState"
```

---

## Task 2: `ContextCompactor` — Layer 1 (persist large output)

**Files:**
- Create: `src/main/java/org/example/agent/engine/ContextCompactor.java`
- Create: `src/test/java/org/example/agent/engine/ContextCompactorTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/example/agent/engine/ContextCompactorTest.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.example.agent.tool.todo.PlanItem;
import org.example.agent.tool.todo.PlanStatus;
import org.example.agent.tool.todo.PlanningState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompactorTest {

    // ── Layer 1: persistIfLarge ──────────────────────────────────────

    @Test
    void persistIfLarge_shortContent_returnedUnchanged(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var content = "short content";
        assertEquals(content, compactor.persistIfLarge("id1", content));
        assertFalse(Files.exists(tempDir.resolve("id1.txt")));
    }

    @Test
    void persistIfLarge_exactlyAtThreshold_returnedUnchanged(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var content = "x".repeat(ContextCompactor.PERSIST_THRESHOLD);
        assertEquals(content, compactor.persistIfLarge("id2", content));
        assertFalse(Files.exists(tempDir.resolve("id2.txt")));
    }

    @Test
    void persistIfLarge_overThreshold_writesFileAndReturnsMarker(@TempDir Path tempDir)
            throws IOException {
        var compactor = new ContextCompactor(tempDir);
        var content = "x".repeat(ContextCompactor.PERSIST_THRESHOLD + 1);

        var result = compactor.persistIfLarge("id3", content);

        assertTrue(Files.exists(tempDir.resolve("id3.txt")));
        assertEquals(content, Files.readString(tempDir.resolve("id3.txt")));
        assertTrue(result.contains("<persisted-output>"));
        assertTrue(result.contains("id3.txt"));
        // preview is exactly the first 2000 chars
        var preview = content.substring(0, 2000);
        assertTrue(result.contains(preview));
    }
}
```

- [ ] **Step 2: Run to confirm compilation fails**

Run: `mvn test -Dtest=ContextCompactorTest`

Expected: compile error — `ContextCompactor` does not exist.

- [ ] **Step 3: Create `ContextCompactor` with Layer 1**

Create `src/main/java/org/example/agent/engine/ContextCompactor.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.example.agent.tool.todo.PlanItem;
import org.example.agent.tool.todo.PlanStatus;
import org.example.agent.tool.todo.PlanningState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ContextCompactor {

    static final int PERSIST_THRESHOLD = 10_000;
    static final int MICRO_KEEP_RECENT = 3;
    static final String PLACEHOLDER = "[Earlier tool result omitted for brevity]";

    private final Path outputDir;

    public ContextCompactor(Path outputDir) {
        this.outputDir = outputDir;
    }

    public String persistIfLarge(String toolUseId, String content) {
        if (content.length() <= PERSIST_THRESHOLD) {
            return content;
        }
        try {
            Files.createDirectories(outputDir);
            var file = outputDir.resolve(toolUseId + ".txt");
            Files.writeString(file, content);
            var preview = content.substring(0, Math.min(2000, content.length()));
            return "<persisted-output>\nFull output saved to: " + file
                    + "\nPreview:\n" + preview + "\n</persisted-output>";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Message> microCompact(List<Message> messages) {
        // placeholder — implemented in Task 3
        return List.copyOf(messages);
    }

    public List<Message> fullCompact(List<Message> messages, PlanningState plan) {
        // placeholder — implemented in Task 4
        return List.of(Message.user("This conversation was compacted for continuity."));
    }
}
```

- [ ] **Step 4: Run tests to confirm Layer 1 passes**

Run: `mvn test -Dtest=ContextCompactorTest`

Expected: all 3 Layer 1 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/engine/ContextCompactor.java \
        src/test/java/org/example/agent/engine/ContextCompactorTest.java
git commit -m "feat(engine): add ContextCompactor with Layer 1 persist-large-output"
```

---

## Task 3: `ContextCompactor` — Layer 2 (micro-compact)

**Files:**
- Modify: `src/main/java/org/example/agent/engine/ContextCompactor.java`
- Modify: `src/test/java/org/example/agent/engine/ContextCompactorTest.java`

- [ ] **Step 1: Write the failing tests**

Add these tests and helpers to `ContextCompactorTest.java`:

```java
    // ── Layer 2: microCompact ────────────────────────────────────────

    @Test
    void microCompact_fewerThanThreshold_returnsUnchanged(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var messages = List.of(
                Message.user("task"),
                toolResultMsg("id1", "r1"),
                toolResultMsg("id2", "r2"),
                toolResultMsg("id3", "r3")  // exactly MICRO_KEEP_RECENT
        );

        var result = compactor.microCompact(messages);

        assertEquals("r1", toolResultContent(result, 1));
        assertEquals("r3", toolResultContent(result, 3));
    }

    @Test
    void microCompact_olderResultsReplacedWithPlaceholder(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var messages = List.of(
                Message.user("task"),
                toolResultMsg("id1", "old1"),  // to be compacted
                toolResultMsg("id2", "old2"),  // to be compacted
                toolResultMsg("id3", "r3"),    // keep
                toolResultMsg("id4", "r4"),    // keep
                toolResultMsg("id5", "r5")     // keep
        );

        var result = compactor.microCompact(messages);

        assertEquals(ContextCompactor.PLACEHOLDER, toolResultContent(result, 1));
        assertEquals(ContextCompactor.PLACEHOLDER, toolResultContent(result, 2));
        assertEquals("r3", toolResultContent(result, 3));
        assertEquals("r5", toolResultContent(result, 5));
    }

    @Test
    void microCompact_nonToolMessages_leftUntouched(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var msg = Message.user("plain text");

        var result = compactor.microCompact(List.of(msg));

        assertEquals(1, result.size());
        assertEquals(msg, result.get(0));
    }

    @Test
    void microCompact_returnsNewList_doesNotMutateOriginal(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var original = new ArrayList<>(List.of(
                Message.user("task"),
                toolResultMsg("a", "val"),
                toolResultMsg("b", "val"),
                toolResultMsg("c", "val"),
                toolResultMsg("d", "val")  // 4 tool-result messages > MICRO_KEEP_RECENT
        ));

        compactor.microCompact(original);

        // original unchanged
        assertEquals("val", toolResultContent(original, 1));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static Message toolResultMsg(String id, String content) {
        return new Message(Role.USER, List.of(new ContentBlock.ToolResult(id, content)));
    }

    private static String toolResultContent(List<Message> messages, int idx) {
        return ((ContentBlock.ToolResult) messages.get(idx).content().get(0)).content();
    }
```

- [ ] **Step 2: Run to confirm they fail**

Run: `mvn test -Dtest=ContextCompactorTest`

Expected: Layer 2 tests FAIL (placeholder impl always returns list unchanged).

- [ ] **Step 3: Implement `microCompact`**

Replace the placeholder `microCompact` method in `ContextCompactor.java`:

```java
public List<Message> microCompact(List<Message> messages) {
    var toolResultIndices = new ArrayList<Integer>();
    for (int i = 0; i < messages.size(); i++) {
        var msg = messages.get(i);
        if (msg.role() == Role.USER
                && msg.content().stream().anyMatch(b -> b instanceof ContentBlock.ToolResult)) {
            toolResultIndices.add(i);
        }
    }

    if (toolResultIndices.size() <= MICRO_KEEP_RECENT) {
        return List.copyOf(messages);
    }

    var toCompact = new HashSet<>(
            toolResultIndices.subList(0, toolResultIndices.size() - MICRO_KEEP_RECENT));

    var result = new ArrayList<Message>(messages.size());
    for (int i = 0; i < messages.size(); i++) {
        if (toCompact.contains(i)) {
            var msg = messages.get(i);
            var newBlocks = msg.content().stream()
                    .map(b -> b instanceof ContentBlock.ToolResult tr
                            ? new ContentBlock.ToolResult(tr.toolUseId(), PLACEHOLDER)
                            : b)
                    .toList();
            result.add(new Message(msg.role(), newBlocks));
        } else {
            result.add(messages.get(i));
        }
    }
    return List.copyOf(result);
}
```

- [ ] **Step 4: Run tests to confirm Layer 2 passes**

Run: `mvn test -Dtest=ContextCompactorTest`

Expected: all 7 tests pass (3 Layer 1 + 4 Layer 2).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/engine/ContextCompactor.java \
        src/test/java/org/example/agent/engine/ContextCompactorTest.java
git commit -m "feat(engine): add Layer 2 micro-compact to ContextCompactor"
```

---

## Task 4: `ContextCompactor` — Layer 3 (full compact)

**Files:**
- Modify: `src/main/java/org/example/agent/engine/ContextCompactor.java`
- Modify: `src/test/java/org/example/agent/engine/ContextCompactorTest.java`

- [ ] **Step 1: Write the failing tests**

Add these tests to `ContextCompactorTest.java`:

```java
    // ── Layer 3: fullCompact ─────────────────────────────────────────

    @Test
    void fullCompact_returnsSingleUserMessage(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var messages = List.of(
                Message.user("build a calculator"),
                Message.assistant("Let me start.")
        );

        var result = compactor.fullCompact(messages, new PlanningState());

        assertEquals(1, result.size());
        assertEquals(Role.USER, result.get(0).role());
        var text = ((ContentBlock.Text) result.get(0).content().get(0)).text();
        assertTrue(text.startsWith("This conversation was compacted for continuity."));
        assertTrue(text.contains("build a calculator"));
        assertTrue(text.contains("Let me start."));
    }

    @Test
    void fullCompact_includesCompletedAndPendingPlanItems(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var plan = new PlanningState();
        plan.update(List.of(
                new PlanItem("step 1", PlanStatus.COMPLETED, "completing step 1"),
                new PlanItem("step 2", PlanStatus.PENDING, "doing step 2")
        ));

        var result = compactor.fullCompact(List.of(Message.user("task")), plan);
        var text = ((ContentBlock.Text) result.get(0).content().get(0)).text();

        assertTrue(text.contains("step 1"));
        assertTrue(text.contains("step 2"));
        assertTrue(text.contains("Completed Actions"));
        assertTrue(text.contains("Pending Tasks"));
    }

    @Test
    void fullCompact_extractsPersistedFilePaths(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var marker = "<persisted-output>\nFull output saved to: /tmp/abc.txt\nPreview:\nhello\n</persisted-output>";
        var messages = List.of(
                Message.user("task"),
                new Message(Role.USER, List.of(new ContentBlock.ToolResult("id1", marker)))
        );

        var result = compactor.fullCompact(messages, new PlanningState());
        var text = ((ContentBlock.Text) result.get(0).content().get(0)).text();

        assertTrue(text.contains("/tmp/abc.txt"));
        assertTrue(text.contains("Persisted Files"));
    }

    @Test
    void fullCompact_emptyPlan_skipsCompletedAndPendingSections(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var messages = List.of(Message.user("task"));

        var result = compactor.fullCompact(messages, new PlanningState());
        var text = ((ContentBlock.Text) result.get(0).content().get(0)).text();

        assertFalse(text.contains("Completed Actions"));
        assertFalse(text.contains("Pending Tasks"));
    }
```

- [ ] **Step 2: Run to confirm they fail**

Run: `mvn test -Dtest=ContextCompactorTest`

Expected: Layer 3 tests FAIL (placeholder returns generic one-liner).

- [ ] **Step 3: Implement `fullCompact` and `extractPersistedPaths`**

Replace the placeholder `fullCompact` method and add `extractPersistedPaths` in `ContextCompactor.java`:

```java
public List<Message> fullCompact(List<Message> messages, PlanningState plan) {
    var sb = new StringBuilder();
    sb.append("This conversation was compacted for continuity.\n\n");
    sb.append("## Compacted Context\n\n");

    // Current Goal: first user text message
    messages.stream()
            .filter(m -> m.role() == Role.USER)
            .findFirst()
            .flatMap(m -> m.content().stream()
                    .filter(b -> b instanceof ContentBlock.Text)
                    .map(b -> ((ContentBlock.Text) b).text())
                    .findFirst())
            .ifPresent(goal -> sb.append("### Current Goal\n").append(goal).append("\n\n"));

    // Completed Actions
    var completed = plan.items().stream()
            .filter(i -> i.status() == PlanStatus.COMPLETED)
            .toList();
    if (!completed.isEmpty()) {
        sb.append("### Completed Actions\n");
        completed.forEach(i -> sb.append("- ").append(i.content()).append("\n"));
        sb.append("\n");
    }

    // Pending Tasks
    var pending = plan.items().stream()
            .filter(i -> i.status() != PlanStatus.COMPLETED)
            .toList();
    if (!pending.isEmpty()) {
        sb.append("### Pending Tasks\n");
        pending.forEach(i -> sb.append("- ").append(i.content()).append("\n"));
        sb.append("\n");
    }

    // Persisted Files
    var persistedPaths = extractPersistedPaths(messages);
    if (!persistedPaths.isEmpty()) {
        sb.append("### Persisted Files\n");
        persistedPaths.forEach(p -> sb.append("- ").append(p).append("\n"));
        sb.append("\n");
    }

    // Recent Assistant Output: last assistant Text block, first 1000 chars
    for (int i = messages.size() - 1; i >= 0; i--) {
        var msg = messages.get(i);
        if (msg.role() == Role.ASSISTANT) {
            msg.content().stream()
                    .filter(b -> b instanceof ContentBlock.Text)
                    .map(b -> ((ContentBlock.Text) b).text())
                    .findFirst()
                    .ifPresent(text -> {
                        var preview = text.length() > 1000 ? text.substring(0, 1000) : text;
                        sb.append("### Recent Assistant Output\n").append(preview);
                    });
            break;
        }
    }

    return List.of(Message.user(sb.toString().stripTrailing()));
}

private List<String> extractPersistedPaths(List<Message> messages) {
    var paths = new ArrayList<String>();
    for (var msg : messages) {
        for (var block : msg.content()) {
            String text = switch (block) {
                case ContentBlock.Text t -> t.text();
                case ContentBlock.ToolResult tr -> tr.content();
                default -> null;
            };
            if (text != null && text.contains("<persisted-output>")) {
                for (var line : text.split("\n")) {
                    if (line.startsWith("Full output saved to: ")) {
                        paths.add(line.substring("Full output saved to: ".length()).trim());
                    }
                }
            }
        }
    }
    return List.copyOf(paths);
}
```

Also add these missing imports to `ContextCompactor.java`:

```java
import org.example.agent.tool.todo.PlanStatus;
```

- [ ] **Step 4: Run all compactor tests**

Run: `mvn test -Dtest=ContextCompactorTest`

Expected: all 11 tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/engine/ContextCompactor.java \
        src/test/java/org/example/agent/engine/ContextCompactorTest.java
git commit -m "feat(engine): add Layer 3 full-compact to ContextCompactor"
```

---

## Task 5: Wire `ContextCompactor` into `QueryEngine`

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Create: `src/test/java/org/example/agent/engine/QueryEngineCompactTest.java`
- Modify: `src/test/java/org/example/agent/engine/QueryEngineTest.java`

### Background on the changes

`QueryEngine.run()` currently uses `state` and `ctx` as **local variables**. We promote them to **instance fields** so that `CompactTool` (Task 6) can access them via lambdas from the constructor. Three other changes:

1. **Top of loop**: call `compactor.microCompact()` before every model call.
2. **`buildToolResultMessage()`**: call `compactor.persistIfLarge()` per tool result.
3. **`decide()`**: return `CompactRetry` instead of `MaxTokensRecovery` when `!hasAttemptedCompact`.
4. **`advance(CompactRetry)`**: full-compact the message list and retry.

This changes the behavior of the existing `max_tokens_appends_continue_prompt_and_resumes` test in `QueryEngineTest` — that test must be updated.

- [ ] **Step 1: Write the failing integration tests**

Create `src/test/java/org/example/agent/engine/QueryEngineCompactTest.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineCompactTest {

    private QueryParams params(String msg) {
        return new QueryParams(List.of(Message.user(msg)), null, null, null, null);
    }

    // MAX_TOKENS (first time) → compact → model retried → END_TURN
    @Test
    void first_max_tokens_triggers_compact_and_retries(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var registry = new ToolRegistry();
        var callCount = new int[]{0};

        var responses = new ModelResponse[]{
                new ModelResponse(List.of(new ContentBlock.Text("Part 1...")),
                        StopReason.MAX_TOKENS, 10, 100),
                new ModelResponse(List.of(new ContentBlock.Text("Done.")),
                        StopReason.END_TURN, 10, 20)
        };

        var engine = new QueryEngine(
                request -> responses[callCount[0]++],
                registry,
                compactor,
                Executors.newSingleThreadExecutor()
        );

        var result = engine.run(params("Write something long"));

        assertInstanceOf(QueryResult.Success.class, result);
        assertEquals(2, callCount[0]);  // compact + retry

        // After compact: messages = [compacted-user, assistant("Done.")]
        var messages = ((QueryResult.Success) result).messages();
        assertEquals(2, messages.size());
        assertEquals(Role.USER, messages.get(0).role());
        var compactedText = ((ContentBlock.Text) messages.get(0).content().get(0)).text();
        assertTrue(compactedText.startsWith("This conversation was compacted for continuity."));
    }

    // MAX_TOKENS after compact already attempted → fall back to MaxTokensRecovery ("Please continue.")
    @Test
    void max_tokens_after_compact_falls_back_to_continue_prompt(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var registry = new ToolRegistry();
        var callCount = new int[]{0};

        var responses = new ModelResponse[]{
                new ModelResponse(List.of(new ContentBlock.Text("P1")),
                        StopReason.MAX_TOKENS, 10, 100),  // triggers compact
                new ModelResponse(List.of(new ContentBlock.Text("P2")),
                        StopReason.MAX_TOKENS, 10, 100),  // triggers MaxTokensRecovery
                new ModelResponse(List.of(new ContentBlock.Text("Done.")),
                        StopReason.END_TURN, 10, 5)
        };

        var engine = new QueryEngine(
                request -> responses[callCount[0]++],
                registry,
                compactor,
                Executors.newSingleThreadExecutor()
        );

        var result = engine.run(params("Write something very long"));

        assertInstanceOf(QueryResult.Success.class, result);
        assertEquals(3, callCount[0]);  // compact, continue-prompt, end_turn

        var messages = ((QueryResult.Success) result).messages();
        // compacted-user, assistant("P2"), user("Please continue."), assistant("Done.")
        assertEquals(4, messages.size());
        var continueMsg = (ContentBlock.Text) messages.get(2).content().get(0);
        assertEquals("Please continue.", continueMsg.text());
    }
}
```

- [ ] **Step 2: Update the existing MAX_TOKENS test in `QueryEngineTest`**

In `QueryEngineTest.java`, replace the body of `max_tokens_appends_continue_prompt_and_resumes` with the updated expectation (compact fires first, then END_TURN on retry — no "Please continue." on first MAX_TOKENS):

```java
@Test
void max_tokens_first_occurrence_triggers_compact_and_retries() {
    var registry = new ToolRegistry();

    var responses = new ModelResponse[]{
            new ModelResponse(List.of(new ContentBlock.Text("Part 1...")),
                    StopReason.MAX_TOKENS, 10, 100),
            new ModelResponse(List.of(new ContentBlock.Text("...Part 2.")),
                    StopReason.END_TURN, 10, 20)
    };
    var idx = new int[]{0};

    var engine = new QueryEngine(request -> responses[idx[0]++], registry);
    var result = engine.run(params("Write a long essay"));

    assertInstanceOf(QueryResult.Success.class, result);
    // messages: compacted-user + final assistant
    var success = (QueryResult.Success) result;
    assertEquals(2, success.messages().size());
    assertEquals(2, idx[0]);
    assertEquals(Role.ASSISTANT, success.messages().get(1).role());
}
```

- [ ] **Step 3: Run the new tests to confirm they fail**

Run: `mvn test -Dtest=QueryEngineCompactTest`

Expected: compile error — the 4-arg `QueryEngine` constructor does not exist yet.

- [ ] **Step 4: Rewrite `QueryEngine.java`**

Replace the entire content of `QueryEngine.java` with:

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelClient;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.*;
import org.example.agent.tool.skill.SkillRegistry;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

public class QueryEngine {

    private static final String CONTINUE_PROMPT = "Please continue.";
    private static final String REMINDER_TEXT =
            "<reminder>Refresh your todo plan before continuing.</reminder>";

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionRuntime runtime;
    private final SkillRegistry skillRegistry;
    private final ContextCompactor compactor;

    // Promoted to instance fields so CompactTool lambdas (wired in Task 6) can read live values.
    // Only valid during an active run() call.
    private QueryState     currentState;
    private ToolUseContext currentCtx;

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this(modelClient, toolRegistry, null, defaultCompactor(), ForkJoinPool.commonPool());
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, ExecutorService executor) {
        this(modelClient, toolRegistry, null, defaultCompactor(), executor);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                       SkillRegistry skillRegistry) {
        this(modelClient, toolRegistry, skillRegistry, defaultCompactor(), ForkJoinPool.commonPool());
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                ContextCompactor compactor, ExecutorService executor) {
        this(modelClient, toolRegistry, null, compactor, executor);
    }

    private QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                        SkillRegistry skillRegistry, ContextCompactor compactor,
                        ExecutorService executor) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.compactor = compactor;
        var router = new ToolRouter(toolRegistry);
        this.runtime = new ToolExecutionRuntime(router, executor);
    }

    private static ContextCompactor defaultCompactor() {
        var dir = Paths.get(System.getProperty("user.dir"),
                ".task_outputs", "tool-results");
        return new ContextCompactor(dir);
    }

    public QueryResult run(QueryParams params) {
        currentState = QueryState.from(params);
        currentCtx   = ToolUseContext.defaults(System.getProperty("user.dir"));

        while (true) {
            // Layer 2: trim old tool results before every model call
            currentState.replaceMessages(compactor.microCompact(currentState.messages()));

            if (params.maxTurns() != null && currentState.turnCount() > params.maxTurns()) {
                return new QueryResult.Success(currentState.messages(), currentState.turnCount());
            }

            var response = modelClient.call(buildRequest(currentState, params));

            if (response.stopReason() == StopReason.TOOL_USE) {
                var toolUses = response.content().stream()
                        .filter(b -> b instanceof ContentBlock.ToolUse)
                        .map(b -> (ContentBlock.ToolUse) b)
                        .toList();
                if (toolUses.isEmpty()) {
                    currentState.appendMessage(new Message(Role.ASSISTANT, response.content()));
                    return new QueryResult.Success(currentState.messages(), currentState.turnCount());
                }
                currentCtx.planningState().tickRound();
                var execResult = runtime.execute(toolUses, currentCtx);
                currentCtx = execResult.updatedContext();
                currentState.appendMessage(new Message(Role.ASSISTANT, response.content()));
                currentState.appendMessage(
                        buildToolResultMessage(execResult.toolResults(),
                                currentCtx.planningState().needsReminder()));
                currentState.setLastTransition(
                        new TransitionReason.ToolResultContinuation(execResult.toolResults()));
                currentState.incrementTurn();
            } else {
                var transition = decide(currentState, response);
                if (transition == null) {
                    currentState.appendMessage(new Message(Role.ASSISTANT, response.content()));
                    return new QueryResult.Success(currentState.messages(), currentState.turnCount());
                }
                advance(currentState, transition, response);
            }
        }
    }

    private TransitionReason decide(QueryState state, ModelResponse response) {
        return switch (response.stopReason()) {
            case END_TURN -> null;
            case TOOL_USE -> throw new IllegalStateException("TOOL_USE handled in run()");
            case MAX_TOKENS -> state.hasAttemptedCompact()
                    ? new TransitionReason.MaxTokensRecovery(state.continuationCount() + 1)
                    : new TransitionReason.CompactRetry();
            case STOP_SEQUENCE -> null;
        };
    }

    private void advance(QueryState state, TransitionReason t, ModelResponse response) {
        switch (t) {
            case TransitionReason.MaxTokensRecovery m -> {
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                state.appendMessage(Message.user(CONTINUE_PROMPT));
                state.incrementContinuation();
                state.setLastTransition(t);
                state.incrementTurn();
            }
            case TransitionReason.CompactRetry c -> {
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                var compacted = compactor.fullCompact(state.messages(), currentCtx.planningState());
                state.replaceMessages(compacted);
                state.markCompactAttempted();
                state.setLastTransition(c);
                // intentionally no incrementTurn — retry immediately
            }
            case TransitionReason.TransportRetry r  -> { /* s11 extension */ }
            case TransitionReason.StopHookContinuation h -> { /* s08 extension */ }
            case TransitionReason.BudgetContinuation b   -> { /* budget extension */ }
            case TransitionReason.ToolResultContinuation c ->
                    throw new IllegalStateException("ToolResultContinuation should not reach advance()");
        }
    }

    private ModelRequest buildRequest(QueryState state, QueryParams params) {
        Integer maxTokens = state.maxOutputTokensOverride()
                .orElse(params.maxOutputTokensOverride());
        return new ModelRequest(
                MessageNormalizer.normalize(state.messages()),
                augmentSystemPrompt(params.systemPrompt()),
                toolRegistry.definitions(),
                maxTokens
        );
    }

    private String augmentSystemPrompt(String base) {
        if (skillRegistry == null) return base;
        var skillSection = skillRegistry.describeAvailable();
        if (skillSection.isEmpty()) return base;
        if (base == null || base.isEmpty()) return skillSection;
        return skillSection + "\n\n" + base;
    }

    private Message buildToolResultMessage(List<ContentBlock.ToolResult> results,
                                           boolean prependReminder) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (prependReminder) {
            blocks.add(new ContentBlock.Text(REMINDER_TEXT));
        }
        // Layer 1: persist large tool outputs to disk
        for (var r : results) {
            var content = compactor.persistIfLarge(r.toolUseId(), r.content());
            blocks.add(new ContentBlock.ToolResult(r.toolUseId(), content));
        }
        return new Message(Role.USER, List.copyOf(blocks));
    }
}
```

- [ ] **Step 5: Run all engine tests**

Run: `mvn test -Dtest="QueryEngineTest,QueryEngineCompactTest,QueryEngineReminderTest,QueryEngineSkillTest"`

Expected: all pass.

- [ ] **Step 6: Run full test suite to check no regressions**

Run: `mvn test`

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/engine/QueryEngineCompactTest.java \
        src/test/java/org/example/agent/engine/QueryEngineTest.java
git commit -m "feat(engine): wire ContextCompactor into QueryEngine loop"
```

---

## Task 6: Implement and wire `CompactTool`

**Files:**
- Create: `src/main/java/org/example/agent/tool/CompactTool.java`
- Create: `src/test/java/org/example/agent/tool/CompactToolTest.java`
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/example/agent/tool/CompactToolTest.java`:

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.example.agent.engine.ContextCompactor;
import org.example.agent.tool.todo.PlanningState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompactToolTest {

    @Test
    void execute_replacesMessagesWithCompactedSummary(@TempDir Path tempDir) {
        var messages = new ArrayList<>(List.of(
                Message.user("original task"),
                Message.assistant("doing it")
        ));
        var tool = new CompactTool(
                new ContextCompactor(tempDir),
                () -> List.copyOf(messages),
                msgs -> { messages.clear(); messages.addAll(msgs); },
                PlanningState::new
        );

        var result = tool.execute(Map.of(), ToolUseContext.defaults("."));

        assertTrue(result.ok());
        assertEquals(1, messages.size());
        assertEquals(Role.USER, messages.get(0).role());
        var text = ((ContentBlock.Text) messages.get(0).content().get(0)).text();
        assertTrue(text.startsWith("This conversation was compacted for continuity."));
        assertTrue(text.contains("original task"));
    }

    @Test
    void compact_tool_is_not_concurrency_safe(@TempDir Path tempDir) {
        var tool = new CompactTool(
                new ContextCompactor(tempDir),
                List::of,
                msgs -> {},
                PlanningState::new
        );
        assertFalse(tool.isConcurrencySafe());
    }

    @Test
    void compact_tool_definition_has_expected_name(@TempDir Path tempDir) {
        var tool = new CompactTool(
                new ContextCompactor(tempDir),
                List::of,
                msgs -> {},
                PlanningState::new
        );
        assertEquals("compact", tool.definition().name());
    }
}
```

- [ ] **Step 2: Run to confirm compilation fails**

Run: `mvn test -Dtest=CompactToolTest`

Expected: compile error — `CompactTool` does not exist.

- [ ] **Step 3: Create `CompactTool`**

Create `src/main/java/org/example/agent/tool/CompactTool.java`:

```java
package org.example.agent.tool;

import org.example.agent.core.Message;
import org.example.agent.core.ToolDefinition;
import org.example.agent.engine.ContextCompactor;
import org.example.agent.tool.todo.PlanningState;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class CompactTool implements Tool {

    private final ContextCompactor compactor;
    private final Supplier<List<Message>> messagesReader;
    private final Consumer<List<Message>> messagesWriter;
    private final Supplier<PlanningState> planReader;

    public CompactTool(ContextCompactor compactor,
                       Supplier<List<Message>> messagesReader,
                       Consumer<List<Message>> messagesWriter,
                       Supplier<PlanningState> planReader) {
        this.compactor = compactor;
        this.messagesReader = messagesReader;
        this.messagesWriter = messagesWriter;
        this.planReader = planReader;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "compact",
                "Compact the conversation history to free up context space.",
                Map.of()
        );
    }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var compacted = compactor.fullCompact(messagesReader.get(), planReader.get());
        messagesWriter.accept(compacted);
        return ToolResultEnvelope.success("Context compacted successfully.");
    }

    @Override
    public boolean isConcurrencySafe() {
        return false;
    }
}
```

- [ ] **Step 4: Run CompactTool tests to confirm they pass**

Run: `mvn test -Dtest=CompactToolTest`

Expected: all 3 tests pass.

- [ ] **Step 5: Wire `CompactTool` into `QueryEngine` constructor**

In the private constructor of `QueryEngine.java`, add one line after `this.runtime = ...`:

```java
toolRegistry.register(new CompactTool(
        compactor,
        () -> List.copyOf(currentState.messages()),
        msgs -> currentState.replaceMessages(msgs),
        () -> currentCtx.planningState()
));
```

`currentState` and `currentCtx` are instance fields — the lambda captures `this` and reads live values when called during `run()`.

- [ ] **Step 6: Run the full test suite**

Run: `mvn test`

Expected: all tests pass.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/agent/tool/CompactTool.java \
        src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/tool/CompactToolTest.java
git commit -m "feat(tool): add CompactTool and wire into QueryEngine"
```
