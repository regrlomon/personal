# s10a Message & Prompt Pipeline — Design

**Date:** 2026-04-28  
**Scope:** Formalize the three-lane input pipeline described in s10a: system prompt blocks / normalized messages / attachments + reminders.  
**Approach:** Option B — introduce a standalone `MessagePipeline` class.

---

## Background

After s10, `SystemPromptBuilder` owns the system prompt assembly. However, the message-side pipeline is still scattered inside `QueryEngine`:

- `REMINDER_TEXT` is hardcoded and prepended inside `buildToolResultMessage()`
- Hook inject messages are appended ad-hoc as `Message.user()` with no formal abstraction
- There is no concept of `Attachment` or `ReminderMessage` in the codebase

s10a defines the complete model input as three parallel lanes:

```
System Prompt Blocks  →  promptBuilder.build()
Messages              →  messagePipeline.build()
Tools                 →  toolRegistry.definitions()
```

This design formalizes the message lane.

---

## New Data Types

### `ReminderMessage` (`engine/ReminderMessage.java`)

A temporary, single-turn injection. Lives only for the current model call.

```java
public record ReminderMessage(String text) {
    public ContentBlock.Text toBlock() {
        return new ContentBlock.Text(text);
    }
}
```

### `Attachment` (`engine/Attachment.java`)

A large, optional supplementary block surfaced as a standalone user message prepended to the message list.

```java
public record Attachment(String label, String content) {
    public Message toMessage() {
        return Message.user("[" + label + "]\n" + content);
    }
}
```

**Current use case:** hook `inject` outputs (future). Memory remains in the system prompt.  
**Extension point:** `collectAttachments()` in `QueryEngine` — populated in a later session when large hook outputs arrive.

---

## `MessagePipeline` (`engine/MessagePipeline.java`)

Three sequential steps mapping directly to s10a's `build_messages()`:

```java
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

### Step responsibilities

| Step | Does | Does not touch |
|------|------|----------------|
| `normalize` | Fill orphaned tool_use, merge consecutive same-role | System prompt |
| `prependAttachments` | Convert each Attachment to a user message, insert before all others | System prompt, reminders |
| `appendReminders` | Append reminder blocks to the last message's content | System prompt, attachments |

`MessageNormalizer` is unchanged — it remains a pure normalize utility called by step 1.

---

## `QueryEngine` Changes

### New field

```java
private final MessagePipeline messagePipeline;
// initialized in the private constructor:
this.messagePipeline = new MessagePipeline();
```

### `buildRequest()` — refactored

```java
private ModelRequest buildRequest(QueryState state, QueryParams params) {
    var reminders   = collectReminders();
    var attachments = collectAttachments();
    Integer maxTokens = state.maxOutputTokensOverride()
            .orElse(params.maxOutputTokensOverride());
    return new ModelRequest(
            messagePipeline.build(state.messages(), attachments, reminders),
            promptBuilder.build(params.systemPrompt()),
            toolRegistry.definitions(),
            maxTokens
    );
}
```

The three parallel input lanes are now visible at a glance.

### `collectReminders()` and `collectAttachments()`

```java
private List<ReminderMessage> collectReminders() {
    if (currentCtx != null && currentCtx.planningState().needsReminder()) {
        return List.of(new ReminderMessage(REMINDER_TEXT));
    }
    return List.of();
}

private List<Attachment> collectAttachments() {
    return List.of();   // extension point for future large hook outputs
}
```

### `buildToolResultMessage()` — simplified

Remove the `prependReminder` parameter and its block-prepend logic. Reminder injection is now the pipeline's responsibility.

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

Call site drops the `needsReminder()` argument:

```java
currentState.appendMessage(buildToolResultMessage(execResult.toolResults()));
```

### Hook inject messages

`execResult.injectionMessages()` continues to be appended as `Message.user()` into `currentState` (raw message list). They flow through `normalize()` in the next turn. Large hook outputs will be promoted to `Attachment` via `collectAttachments()` in a future session.

---

## Data Flow

```
QueryEngine.run()
  │
  ├── collectReminders()   → List<ReminderMessage>
  ├── collectAttachments() → List<Attachment>
  │
  └── buildRequest()
        ├── messagePipeline.build(state.messages(), attachments, reminders)
        │     ├── normalize(raw)              [MessageNormalizer]
        │     ├── prependAttachments(...)
        │     └── appendReminders(...)
        ├── promptBuilder.build(systemPrompt) [SystemPromptBuilder]
        └── toolRegistry.definitions()
```

---

## What Is NOT Changing

- `SystemPromptBuilder` — untouched; memory stays in system prompt
- `MessageNormalizer` — untouched; called as step 1 inside `MessagePipeline`
- Hook inject message routing — stays as `Message.user()` appended to `currentState`
- `ContextCompactor` — untouched

---

## Testing Strategy

**Precondition for `appendReminders`:** the last message in the list must be a USER message. This holds after `normalize()` in any well-formed conversation (model alternates roles; the final message before a model call is always the user turn). If violated, `appendReminders` will silently append reminder blocks to an ASSISTANT message, which is incorrect — this should be caught in tests.

- `MessagePipelineTest` — unit tests for each step in isolation:
  - `normalize` delegates to `MessageNormalizer` (already tested)
  - `prependAttachments` with empty / non-empty attachment list
  - `appendReminders` with empty / non-empty reminder list; last message is USER (happy path); last message is ASSISTANT (should not happen in practice — document the invariant in a test comment)
- `QueryEngineTest` — existing tests should continue to pass; add a test that a `needsReminder()` state causes the reminder block to appear in the final message sent to `ModelClient`
