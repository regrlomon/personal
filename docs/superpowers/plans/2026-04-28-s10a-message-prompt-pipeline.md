# s10a Message & Prompt Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce `MessagePipeline` with `ReminderMessage` and `Attachment` types, refactor `QueryEngine.buildRequest()` to expose three parallel input lanes: messages / system prompt / tools.

**Architecture:** New `MessagePipeline` chains three steps — normalize → prependAttachments → appendReminders — replacing the scattered reminder and injection logic currently inside `QueryEngine`. `ReminderMessage` and `Attachment` are lightweight records. `QueryEngine.buildRequest()` becomes a three-line coordinator. Reminders are now ephemeral (per-request only, not persisted to state).

**Tech Stack:** Java 21, JUnit Jupiter 5.10, Maven

---

## File Map

| Action | Path | Responsibility |
|--------|------|----------------|
| Create | `src/main/java/org/example/agent/engine/ReminderMessage.java` | Single-turn reminder wrapper |
| Create | `src/main/java/org/example/agent/engine/Attachment.java` | Optional supplementary message block |
| Create | `src/main/java/org/example/agent/engine/MessagePipeline.java` | Three-step message assembly pipeline |
| Create | `src/test/java/org/example/agent/engine/MessagePipelineTest.java` | Unit tests for all three pipeline steps |
| Modify | `src/main/java/org/example/agent/engine/QueryEngine.java` | Wire `MessagePipeline`; refactor `buildRequest()` and `buildToolResultMessage()` |
| Modify | `src/test/java/org/example/agent/engine/QueryEngineReminderTest.java` | Update assertions: reminders are now in model request, not in result state |

---

## Task 1: `ReminderMessage` and `Attachment` records

**Files:**
- Create: `src/main/java/org/example/agent/engine/ReminderMessage.java`
- Create: `src/main/java/org/example/agent/engine/Attachment.java`
- Create: `src/test/java/org/example/agent/engine/MessagePipelineTest.java` (scaffold only)

- [ ] **Step 1: Create `ReminderMessage.java`**

```java
package org.example.agent.engine;

import org.example.agent.core.ContentBlock;

public record ReminderMessage(String text) {
    public ContentBlock.Text toBlock() {
        return new ContentBlock.Text(text);
    }
}
```

- [ ] **Step 2: Create `Attachment.java`**

```java
package org.example.agent.engine;

import org.example.agent.core.Message;

public record Attachment(String label, String content) {
    public Message toMessage() {
        return Message.user("[" + label + "]\n" + content);
    }
}
```

- [ ] **Step 3: Write failing tests for both records**

Create `src/test/java/org/example/agent/engine/MessagePipelineTest.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessagePipelineTest {

    // --- ReminderMessage ---

    @Test
    void reminderMessage_toBlock_returnsTextBlock() {
        var reminder = new ReminderMessage("<reminder>refresh</reminder>");
        var block = reminder.toBlock();
        assertInstanceOf(ContentBlock.Text.class, block);
        assertEquals("<reminder>refresh</reminder>", block.text());
    }

    // --- Attachment ---

    @Test
    void attachment_toMessage_returnsUserMessage() {
        var attachment = new Attachment("memory", "some content");
        var msg = attachment.toMessage();
        assertEquals(Role.USER, msg.role());
        assertEquals(1, msg.content().size());
        var text = (ContentBlock.Text) msg.content().get(0);
        assertEquals("[memory]\nsome content", text.text());
    }
}
```

- [ ] **Step 4: Run tests to verify they fail (classes don't exist yet)**

```
mvn test -Dtest=MessagePipelineTest -f pom.xml
```

Expected: compilation error — `MessagePipelineTest` not found / classes missing.

- [ ] **Step 5: Run tests to verify they pass (records are now created)**

```
mvn test -Dtest=MessagePipelineTest -f pom.xml
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/engine/ReminderMessage.java \
        src/main/java/org/example/agent/engine/Attachment.java \
        src/test/java/org/example/agent/engine/MessagePipelineTest.java
git commit -m "feat(s10a): add ReminderMessage and Attachment records"
```

---

## Task 2: `MessagePipeline` — all three steps

**Files:**
- Create: `src/main/java/org/example/agent/engine/MessagePipeline.java`
- Modify: `src/test/java/org/example/agent/engine/MessagePipelineTest.java`

- [ ] **Step 1: Write failing tests for all three pipeline steps**

Append these tests to `MessagePipelineTest.java` (after the two existing tests):

```java
    // --- normalize (delegates to MessageNormalizer) ---

    @Test
    void build_normalize_mergesConsecutiveUserMessages() {
        var pipeline = new MessagePipeline();
        var raw = List.of(Message.user("a"), Message.user("b"));
        var result = pipeline.build(raw, List.of(), List.of());
        assertEquals(1, result.size(), "consecutive USER messages must be merged");
    }

    // --- prependAttachments ---

    @Test
    void build_emptyAttachments_messagesUnchanged() {
        var pipeline = new MessagePipeline();
        var raw = List.of(Message.user("hello"));
        var result = pipeline.build(raw, List.of(), List.of());
        assertEquals(1, result.size());
        var text = (ContentBlock.Text) result.get(0).content().get(0);
        assertEquals("hello", text.text());
    }

    @Test
    void build_attachmentsPrependedBeforeRawMessages() {
        var pipeline = new MessagePipeline();
        var raw = List.of(Message.user("query"));
        var attachments = List.of(new Attachment("ctx", "extra info"));
        var result = pipeline.build(raw, attachments, List.of());
        assertEquals(2, result.size());
        // first message is the attachment
        var first = (ContentBlock.Text) result.get(0).content().get(0);
        assertEquals("[ctx]\nextra info", first.text());
        // second message is the original query
        var second = (ContentBlock.Text) result.get(1).content().get(0);
        assertEquals("query", second.text());
    }

    // --- appendReminders ---

    @Test
    void build_emptyReminders_messagesUnchanged() {
        var pipeline = new MessagePipeline();
        var raw = List.of(Message.user("hi"));
        var result = pipeline.build(raw, List.of(), List.of());
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).content().size());
    }

    @Test
    void build_reminderAppendedToLastMessage() {
        var pipeline = new MessagePipeline();
        var raw = List.of(
                Message.user("turn 1"),
                Message.assistant("reply"),
                Message.user("turn 2")          // last message — USER
        );
        var reminders = List.of(new ReminderMessage("<reminder>refresh</reminder>"));
        var result = pipeline.build(raw, List.of(), reminders);
        // message count unchanged
        assertEquals(3, result.size());
        // last message now has 2 blocks: original text + reminder
        var last = result.get(2);
        assertEquals(Role.USER, last.role());
        assertEquals(2, last.content().size());
        var reminderBlock = (ContentBlock.Text) last.content().get(1);
        assertEquals("<reminder>refresh</reminder>", reminderBlock.text());
    }
```

- [ ] **Step 2: Run tests to verify they fail (class doesn't exist yet)**

```
mvn test -Dtest=MessagePipelineTest -f pom.xml
```

Expected: compilation error — `MessagePipeline` not found.

- [ ] **Step 3: Create `MessagePipeline.java`**

```java
package org.example.agent.engine;

import org.example.agent.core.Message;
import org.example.agent.core.Role;

import java.util.ArrayList;
import java.util.List;

public class MessagePipeline {

    public List<Message> build(List<Message> raw,
                               List<Attachment> attachments,
                               List<ReminderMessage> reminders) {
        var messages = normalize(raw);
        messages = prependAttachments(messages, attachments);
        messages = appendReminders(messages, reminders);
        return messages;
    }

    private List<Message> normalize(List<Message> raw) {
        return MessageNormalizer.normalize(raw);
    }

    private List<Message> prependAttachments(List<Message> messages,
                                             List<Attachment> attachments) {
        if (attachments.isEmpty()) return messages;
        var result = new ArrayList<Message>();
        attachments.forEach(a -> result.add(a.toMessage()));
        result.addAll(messages);
        return List.copyOf(result);
    }

    private List<Message> appendReminders(List<Message> messages,
                                          List<ReminderMessage> reminders) {
        if (reminders.isEmpty()) return messages;
        // Precondition: last message is always USER in a well-formed conversation.
        // normalize() guarantees alternating roles; the final message before a model
        // call is always the user turn.
        var last = messages.get(messages.size() - 1);
        var blocks = new ArrayList<>(last.content());
        reminders.forEach(r -> blocks.add(r.toBlock()));
        var updated = new Message(last.role(), List.copyOf(blocks));
        var result = new ArrayList<>(messages.subList(0, messages.size() - 1));
        result.add(updated);
        return List.copyOf(result);
    }
}
```

- [ ] **Step 4: Run tests to verify all pass**

```
mvn test -Dtest=MessagePipelineTest -f pom.xml
```

Expected: `Tests run: 7, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/engine/MessagePipeline.java \
        src/test/java/org/example/agent/engine/MessagePipelineTest.java
git commit -m "feat(s10a): add MessagePipeline with normalize/attach/remind steps"
```

---

## Task 3: Refactor `QueryEngine` and update reminder test

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Modify: `src/test/java/org/example/agent/engine/QueryEngineReminderTest.java`

**Key behavioral change:** Reminders are now ephemeral — appended to the `ModelRequest` messages at call time, but NOT stored in `currentState`. `result.messages()` will no longer contain reminder blocks.

**TDD anchor:** The test `reminder_not_persisted_to_state` will be RED with the old code (reminders ARE in state) and GREEN after the refactor (reminders are ephemeral). The other two tests (`reminder_sent_to_model` and `reminder_not_sent_when_todo`) verify the model still sees the correct behavior and pass with both old and new code.

- [ ] **Step 1: Update `QueryEngineReminderTest` to match new contract**

Replace the entire content of `QueryEngineReminderTest.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.*;
import org.example.agent.tool.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineReminderTest {

    private ModelResponse toolResponse(String toolId, String toolName) {
        return new ModelResponse(
                List.of(new ContentBlock.ToolUse(toolId, toolName, Map.of())),
                StopReason.TOOL_USE, 10, 5);
    }

    private ModelResponse endResponse() {
        return new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 10, 5);
    }

    private ToolRegistry readFileRegistry() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("read_file", "", Map.of()); }
            @Override public boolean isConcurrencySafe() { return true; }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("content");
            }
        });
        return registry;
    }

    // --- RED test: verifies reminders are ephemeral (not persisted to state) ---
    // FAILS with old code (reminder IS in state); PASSES after refactor.

    @Test
    void reminder_not_persisted_to_state() {
        var responses = List.of(
                toolResponse("1", "read_file"),
                toolResponse("2", "read_file"),
                toolResponse("3", "read_file"),
                endResponse()
        );
        var idx = new AtomicInteger(0);
        var engine = new QueryEngine(request -> responses.get(idx.getAndIncrement()), readFileRegistry());

        var result = (QueryResult.Success) engine.run(
                new QueryParams(List.of(Message.user("do things")), null, null, null, null));

        // Reminders are ephemeral: they must NOT appear in the persisted state messages.
        var reminderInState = result.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .anyMatch(m -> m.content().stream().anyMatch(
                        b -> b instanceof ContentBlock.Text t && t.text().contains("<reminder>")));
        assertFalse(reminderInState, "reminder must NOT be persisted to state — it is ephemeral");
    }

    // --- Contract test: reminder still reaches the model via the pipeline ---

    @Test
    void reminder_sent_to_model_after_three_rounds_without_todo() {
        var responses = List.of(
                toolResponse("1", "read_file"),
                toolResponse("2", "read_file"),
                toolResponse("3", "read_file"),
                endResponse()
        );
        var idx = new AtomicInteger(0);
        var capturedRequests = new ArrayList<ModelRequest>();

        var engine = new QueryEngine(request -> {
            capturedRequests.add(request);
            return responses.get(idx.getAndIncrement());
        }, readFileRegistry());

        engine.run(new QueryParams(List.of(Message.user("do things")), null, null, null, null));

        var hasReminder = capturedRequests.stream()
                .flatMap(r -> r.messages().stream())
                .filter(m -> m.role() == Role.USER)
                .anyMatch(m -> m.content().stream().anyMatch(
                        b -> b instanceof ContentBlock.Text t && t.text().contains("<reminder>")));
        assertTrue(hasReminder, "reminder must appear in a model request after 3 stale rounds");
    }

    @Test
    void reminder_not_sent_when_todo_called_each_round() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("todo", "", Map.of()); }
            @Override public boolean isConcurrencySafe() { return false; }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                ctx.planningState().update(List.of());
                return ToolResultEnvelope.success("[]");
            }
        });

        var responses = List.of(
                toolResponse("1", "todo"),
                toolResponse("2", "todo"),
                toolResponse("3", "todo"),
                endResponse()
        );
        var idx = new AtomicInteger(0);
        var capturedRequests = new ArrayList<ModelRequest>();

        var engine = new QueryEngine(request -> {
            capturedRequests.add(request);
            return responses.get(idx.getAndIncrement());
        }, registry);

        engine.run(new QueryParams(List.of(Message.user("plan things")), null, null, null, null));

        var hasReminder = capturedRequests.stream()
                .flatMap(r -> r.messages().stream())
                .filter(m -> m.role() == Role.USER)
                .anyMatch(m -> m.content().stream().anyMatch(
                        b -> b instanceof ContentBlock.Text t && t.text().contains("<reminder>")));
        assertFalse(hasReminder, "no reminder expected when todo is called each round");
    }
}
```

- [ ] **Step 2: Run updated reminder test to confirm `reminder_not_persisted_to_state` FAILS**

```
mvn test -Dtest=QueryEngineReminderTest -f pom.xml
```

Expected: `reminder_not_persisted_to_state` FAILS — old code stores the reminder in state.

- [ ] **Step 3: Refactor `QueryEngine`**

Apply these changes to `QueryEngine.java`:

**3a — Add `messagePipeline` field** (after `promptBuilder` field):
```java
private final MessagePipeline messagePipeline;
```

**3b — Initialize in the private constructor** (after `this.promptBuilder = ...` line):
```java
this.messagePipeline = new MessagePipeline();
```

**3c — Replace `buildRequest()`:**
```java
private ModelRequest buildRequest(QueryState state, QueryParams params) {
    var reminders   = collectReminders();
    var attachments = collectAttachments();
    Integer maxTokens = state.maxOutputTokensOverride()
            .orElse(params.maxOutputTokensOverride());
    return new ModelRequest(
            messagePipeline.build(state.messages(), attachments, reminders),
            augmentSystemPrompt(params.systemPrompt()),
            toolRegistry.definitions(),
            maxTokens
    );
}

private List<ReminderMessage> collectReminders() {
    if (currentCtx != null && currentCtx.planningState().needsReminder()) {
        return List.of(new ReminderMessage(REMINDER_TEXT));
    }
    return List.of();
}

private List<Attachment> collectAttachments() {
    return List.of();
}
```

**3d — Replace `buildToolResultMessage()` call site and method:**

In `run()`, change:
```java
currentState.appendMessage(
        buildToolResultMessage(execResult.toolResults(),
                currentCtx.planningState().needsReminder()));
```
to:
```java
currentState.appendMessage(buildToolResultMessage(execResult.toolResults()));
```

Replace the method itself:
```java
private Message buildToolResultMessage(List<ContentBlock.ToolResult> results) {
    List<ContentBlock> blocks = new ArrayList<>();
    for (var r : results) {
        var content = compactor.persistIfLarge(r.toolUseId(), r.content());
        blocks.add(new ContentBlock.ToolResult(r.toolUseId(), content));
    }
    return new Message(Role.USER, List.copyOf(blocks));
}
```

- [ ] **Step 4: Run updated reminder test to confirm it now passes**

```
mvn test -Dtest=QueryEngineReminderTest -f pom.xml
```

Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Run all tests to confirm no regressions**

```
mvn test -f pom.xml
```

Expected: all tests pass — zero failures, zero errors.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/engine/QueryEngineReminderTest.java
git commit -m "feat(s10a): wire MessagePipeline into QueryEngine, extract collectReminders/collectAttachments"
```
