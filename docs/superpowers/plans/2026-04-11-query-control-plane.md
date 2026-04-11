# Query Control Plane Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the Java 21 query control plane framework — four packages (`core`, `model`, `tool`, `engine`) that serve as the extensible base for all future agent chapter implementations.

**Architecture:** `core` holds shared value objects and the mutable state container; `model` and `tool` depend only on `core`; `engine` drives the main loop and is the only package that sees the whole picture. Dependency direction is strictly one-way with no cycles.

**Tech Stack:** Java 21, Maven, JUnit 5 (Jupiter)

---

## File Map

**Created in this plan:**

```
pom.xml                                                           (modified)

src/main/java/org/example/agent/core/
  Role.java
  ContentBlock.java
  Message.java
  ToolDefinition.java
  QueryParams.java
  TransitionReason.java
  QueryState.java

src/main/java/org/example/agent/model/
  StopReason.java
  ModelRequest.java
  ModelResponse.java
  ModelClient.java

src/main/java/org/example/agent/tool/
  Tool.java
  UnknownToolException.java
  ToolRegistry.java

src/main/java/org/example/agent/engine/
  QueryResult.java
  QueryEngine.java

src/test/java/org/example/agent/core/
  MessageTest.java
  QueryStateTest.java
  TransitionReasonTest.java

src/test/java/org/example/agent/tool/
  ToolRegistryTest.java

src/test/java/org/example/agent/engine/
  QueryEngineTest.java
```

---

### Task 1: Add JUnit 5 to pom.xml

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add JUnit 5 dependency and surefire plugin**

Replace the entire content of `pom.xml` with:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>org.example</groupId>
    <artifactId>agent</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <version>5.10.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <version>3.2.5</version>
            </plugin>
        </plugins>
    </build>

</project>
```

- [ ] **Step 2: Verify Maven resolves dependencies**

Run: `mvn dependency:resolve -q`
Expected: BUILD SUCCESS (no errors about missing artifacts)

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add JUnit 5 and surefire plugin"
```

---

### Task 2: core — Role, ContentBlock, Message

**Files:**
- Create: `src/main/java/org/example/agent/core/Role.java`
- Create: `src/main/java/org/example/agent/core/ContentBlock.java`
- Create: `src/main/java/org/example/agent/core/Message.java`
- Test: `src/test/java/org/example/agent/core/MessageTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/example/agent/core/MessageTest.java`:

```java
package org.example.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void user_factory_creates_user_message_with_text_block() {
        var msg = Message.user("hello");
        assertEquals(Role.USER, msg.role());
        assertEquals(1, msg.content().size());
        assertInstanceOf(ContentBlock.Text.class, msg.content().get(0));
        assertEquals("hello", ((ContentBlock.Text) msg.content().get(0)).text());
    }

    @Test
    void assistant_factory_creates_assistant_message_with_text_block() {
        var msg = Message.assistant("hi");
        assertEquals(Role.ASSISTANT, msg.role());
        assertEquals(1, msg.content().size());
        assertInstanceOf(ContentBlock.Text.class, msg.content().get(0));
        assertEquals("hi", ((ContentBlock.Text) msg.content().get(0)).text());
    }

    @Test
    void content_block_tool_use_holds_fields() {
        var toolUse = new ContentBlock.ToolUse("id-1", "my_tool", Map.of("key", "val"));
        assertEquals("id-1", toolUse.id());
        assertEquals("my_tool", toolUse.name());
        assertEquals("val", toolUse.input().get("key"));
    }

    @Test
    void content_block_tool_result_holds_fields() {
        var result = new ContentBlock.ToolResult("id-1", "output text");
        assertEquals("id-1", result.toolUseId());
        assertEquals("output text", result.content());
    }

    @Test
    void message_with_multiple_blocks() {
        var blocks = List.<ContentBlock>of(
            new ContentBlock.Text("thinking..."),
            new ContentBlock.ToolUse("id-2", "calc", Map.of("x", 1))
        );
        var msg = new Message(Role.ASSISTANT, blocks);
        assertEquals(2, msg.content().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=MessageTest`
Expected: FAIL — compilation error (types not yet defined)

- [ ] **Step 3: Create Role.java**

Create `src/main/java/org/example/agent/core/Role.java`:

```java
package org.example.agent.core;

public enum Role {
    USER,
    ASSISTANT
}
```

- [ ] **Step 4: Create ContentBlock.java**

Create `src/main/java/org/example/agent/core/ContentBlock.java`:

```java
package org.example.agent.core;

import java.util.Map;

public sealed interface ContentBlock permits
        ContentBlock.Text,
        ContentBlock.ToolUse,
        ContentBlock.ToolResult {

    record Text(String text) implements ContentBlock {}

    record ToolUse(String id, String name, Map<String, Object> input) implements ContentBlock {}

    record ToolResult(String toolUseId, String content) implements ContentBlock {}
}
```

- [ ] **Step 5: Create Message.java**

Create `src/main/java/org/example/agent/core/Message.java`:

```java
package org.example.agent.core;

import java.util.List;

public record Message(Role role, List<ContentBlock> content) {

    public static Message user(String text) {
        return new Message(Role.USER, List.of(new ContentBlock.Text(text)));
    }

    public static Message assistant(String text) {
        return new Message(Role.ASSISTANT, List.of(new ContentBlock.Text(text)));
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=MessageTest`
Expected: PASS — 5 tests, 0 failures

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/agent/core/Role.java \
        src/main/java/org/example/agent/core/ContentBlock.java \
        src/main/java/org/example/agent/core/Message.java \
        src/test/java/org/example/agent/core/MessageTest.java
git commit -m "feat(core): add Role, ContentBlock, Message"
```

---

### Task 3: core — ToolDefinition, QueryParams

**Files:**
- Create: `src/main/java/org/example/agent/core/ToolDefinition.java`
- Create: `src/main/java/org/example/agent/core/QueryParams.java`

These are pure records with no logic. No dedicated test file — they will be exercised through ToolRegistry and QueryEngine tests. Create them directly.

- [ ] **Step 1: Create ToolDefinition.java**

Create `src/main/java/org/example/agent/core/ToolDefinition.java`:

```java
package org.example.agent.core;

import java.util.Map;

public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema
) {}
```

- [ ] **Step 2: Create QueryParams.java**

Create `src/main/java/org/example/agent/core/QueryParams.java`:

```java
package org.example.agent.core;

import java.util.List;

public record QueryParams(
        List<Message> messages,
        String systemPrompt,
        String fallbackModel,           // nullable
        Integer maxOutputTokensOverride, // nullable
        Integer maxTurns                 // nullable
) {}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/agent/core/ToolDefinition.java \
        src/main/java/org/example/agent/core/QueryParams.java
git commit -m "feat(core): add ToolDefinition and QueryParams records"
```

---

### Task 4: core — TransitionReason

**Files:**
- Create: `src/main/java/org/example/agent/core/TransitionReason.java`
- Test: `src/test/java/org/example/agent/core/TransitionReasonTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/example/agent/core/TransitionReasonTest.java`:

```java
package org.example.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransitionReasonTest {

    @Test
    void tool_result_continuation_holds_results() {
        var result = new ContentBlock.ToolResult("id-1", "output");
        TransitionReason t = new TransitionReason.ToolResultContinuation(List.of(result));
        assertInstanceOf(TransitionReason.ToolResultContinuation.class, t);
        assertEquals(1, ((TransitionReason.ToolResultContinuation) t).results().size());
    }

    @Test
    void max_tokens_recovery_holds_attempt_count() {
        TransitionReason t = new TransitionReason.MaxTokensRecovery(2);
        assertEquals(2, ((TransitionReason.MaxTokensRecovery) t).attempt());
    }

    @Test
    void pattern_matching_switch_is_exhaustive() {
        // If TransitionReason gains a new subtype without a case here, this won't compile.
        TransitionReason t = new TransitionReason.CompactRetry();
        String label = switch (t) {
            case TransitionReason.ToolResultContinuation c -> "tool";
            case TransitionReason.MaxTokensRecovery m      -> "max_tokens";
            case TransitionReason.CompactRetry c           -> "compact";
            case TransitionReason.TransportRetry r         -> "transport";
            case TransitionReason.StopHookContinuation h   -> "stop_hook";
            case TransitionReason.BudgetContinuation b     -> "budget";
        };
        assertEquals("compact", label);
    }

    @Test
    void transport_retry_holds_attempt_and_cause() {
        var cause = new RuntimeException("timeout");
        TransitionReason t = new TransitionReason.TransportRetry(1, cause);
        var retry = (TransitionReason.TransportRetry) t;
        assertEquals(1, retry.attempt());
        assertSame(cause, retry.cause());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TransitionReasonTest`
Expected: FAIL — compilation error (TransitionReason not yet defined)

- [ ] **Step 3: Create TransitionReason.java**

Create `src/main/java/org/example/agent/core/TransitionReason.java`:

```java
package org.example.agent.core;

import java.util.List;

public sealed interface TransitionReason permits
        TransitionReason.ToolResultContinuation,
        TransitionReason.MaxTokensRecovery,
        TransitionReason.CompactRetry,
        TransitionReason.TransportRetry,
        TransitionReason.StopHookContinuation,
        TransitionReason.BudgetContinuation {

    record ToolResultContinuation(List<ContentBlock.ToolResult> results) implements TransitionReason {}

    record MaxTokensRecovery(int attempt) implements TransitionReason {}

    record CompactRetry() implements TransitionReason {}

    record TransportRetry(int attempt, Throwable cause) implements TransitionReason {}

    record StopHookContinuation(String hookName) implements TransitionReason {}

    record BudgetContinuation() implements TransitionReason {}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TransitionReasonTest`
Expected: PASS — 4 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/core/TransitionReason.java \
        src/test/java/org/example/agent/core/TransitionReasonTest.java
git commit -m "feat(core): add TransitionReason sealed interface"
```

---

### Task 5: core — QueryState

**Files:**
- Create: `src/main/java/org/example/agent/core/QueryState.java`
- Test: `src/test/java/org/example/agent/core/QueryStateTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/example/agent/core/QueryStateTest.java`:

```java
package org.example.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryStateTest {

    private QueryParams minimalParams() {
        return new QueryParams(
                List.of(Message.user("hello")),
                "You are helpful.",
                null, null, null
        );
    }

    @Test
    void from_params_initializes_defaults() {
        var state = QueryState.from(minimalParams());
        assertEquals(1, state.messages().size());
        assertEquals(1, state.turnCount());
        assertEquals(0, state.continuationCount());
        assertFalse(state.hasAttemptedCompact());
        assertFalse(state.stopHookActive());
        assertTrue(state.maxOutputTokensOverride().isEmpty());
        assertTrue(state.lastTransition().isEmpty());
    }

    @Test
    void from_params_copies_messages_not_reference() {
        var originalMessages = new java.util.ArrayList<>(List.of(Message.user("hello")));
        var params = new QueryParams(originalMessages, "system", null, null, null);
        var state = QueryState.from(params);

        // Mutate original list — state should be unaffected
        originalMessages.add(Message.user("injected"));
        assertEquals(1, state.messages().size());
    }

    @Test
    void append_message_adds_to_messages() {
        var state = QueryState.from(minimalParams());
        state.appendMessage(Message.assistant("hi"));
        assertEquals(2, state.messages().size());
        assertEquals(Role.ASSISTANT, state.messages().get(1).role());
    }

    @Test
    void messages_returns_unmodifiable_view() {
        var state = QueryState.from(minimalParams());
        assertThrows(UnsupportedOperationException.class,
                () -> state.messages().add(Message.user("hack")));
    }

    @Test
    void increment_turn_increments_count() {
        var state = QueryState.from(minimalParams());
        state.incrementTurn();
        state.incrementTurn();
        assertEquals(3, state.turnCount());
    }

    @Test
    void increment_continuation_increments_count() {
        var state = QueryState.from(minimalParams());
        state.incrementContinuation();
        assertEquals(1, state.continuationCount());
    }

    @Test
    void mark_compact_attempted_flips_flag() {
        var state = QueryState.from(minimalParams());
        state.markCompactAttempted();
        assertTrue(state.hasAttemptedCompact());
    }

    @Test
    void set_last_transition_is_returned_as_present_optional() {
        var state = QueryState.from(minimalParams());
        var t = new TransitionReason.MaxTokensRecovery(1);
        state.setLastTransition(t);
        assertTrue(state.lastTransition().isPresent());
        assertSame(t, state.lastTransition().get());
    }

    @Test
    void from_params_copies_max_output_tokens_override() {
        var params = new QueryParams(List.of(Message.user("q")), "sys", null, 512, null);
        var state = QueryState.from(params);
        assertEquals(512, state.maxOutputTokensOverride().orElseThrow());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=QueryStateTest`
Expected: FAIL — compilation error (QueryState not yet defined)

- [ ] **Step 3: Create QueryState.java**

Create `src/main/java/org/example/agent/core/QueryState.java`:

```java
package org.example.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class QueryState {

    private final List<Message> messages;
    private int turnCount = 1;
    private int continuationCount = 0;
    private boolean hasAttemptedCompact = false;
    private boolean stopHookActive = false;
    private Integer maxOutputTokensOverride;
    private TransitionReason lastTransition;

    private QueryState(List<Message> messages, Integer maxOutputTokensOverride) {
        this.messages = new ArrayList<>(messages);
        this.maxOutputTokensOverride = maxOutputTokensOverride;
    }

    public static QueryState from(QueryParams params) {
        return new QueryState(params.messages(), params.maxOutputTokensOverride());
    }

    // --- Read accessors ---

    public List<Message> messages() {
        return Collections.unmodifiableList(messages);
    }

    public int turnCount() {
        return turnCount;
    }

    public int continuationCount() {
        return continuationCount;
    }

    public boolean hasAttemptedCompact() {
        return hasAttemptedCompact;
    }

    public boolean stopHookActive() {
        return stopHookActive;
    }

    public Optional<Integer> maxOutputTokensOverride() {
        return Optional.ofNullable(maxOutputTokensOverride);
    }

    public Optional<TransitionReason> lastTransition() {
        return Optional.ofNullable(lastTransition);
    }

    // --- Mutation methods ---

    public void appendMessage(Message m) {
        messages.add(m);
    }

    public void incrementTurn() {
        turnCount++;
    }

    public void incrementContinuation() {
        continuationCount++;
    }

    public void markCompactAttempted() {
        hasAttemptedCompact = true;
    }

    public void setStopHookActive(boolean active) {
        stopHookActive = active;
    }

    public void setMaxOutputTokensOverride(Integer v) {
        maxOutputTokensOverride = v;
    }

    public void setLastTransition(TransitionReason t) {
        lastTransition = t;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=QueryStateTest`
Expected: PASS — 9 tests, 0 failures

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/core/QueryState.java \
        src/test/java/org/example/agent/core/QueryStateTest.java
git commit -m "feat(core): add QueryState with named mutation methods"
```

---

### Task 6: model — StopReason, ModelRequest, ModelResponse, ModelClient

**Files:**
- Create: `src/main/java/org/example/agent/model/StopReason.java`
- Create: `src/main/java/org/example/agent/model/ModelRequest.java`
- Create: `src/main/java/org/example/agent/model/ModelResponse.java`
- Create: `src/main/java/org/example/agent/model/ModelClient.java`

These are structural types (enum, records, interface) with no logic. Create directly and verify compilation.

- [ ] **Step 1: Create StopReason.java**

Create `src/main/java/org/example/agent/model/StopReason.java`:

```java
package org.example.agent.model;

public enum StopReason {
    END_TURN,
    TOOL_USE,
    MAX_TOKENS,
    STOP_SEQUENCE
}
```

- [ ] **Step 2: Create ModelRequest.java**

Create `src/main/java/org/example/agent/model/ModelRequest.java`:

```java
package org.example.agent.model;

import org.example.agent.core.Message;
import org.example.agent.core.ToolDefinition;

import java.util.List;

public record ModelRequest(
        List<Message> messages,
        String systemPrompt,
        List<ToolDefinition> tools,
        Integer maxOutputTokens  // nullable
) {}
```

- [ ] **Step 3: Create ModelResponse.java**

Create `src/main/java/org/example/agent/model/ModelResponse.java`:

```java
package org.example.agent.model;

import org.example.agent.core.ContentBlock;

import java.util.List;

public record ModelResponse(
        List<ContentBlock> content,
        StopReason stopReason,
        int inputTokens,
        int outputTokens
) {}
```

- [ ] **Step 4: Create ModelClient.java**

Create `src/main/java/org/example/agent/model/ModelClient.java`:

```java
package org.example.agent.model;

public interface ModelClient {
    ModelResponse call(ModelRequest request);
}
```

- [ ] **Step 5: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/model/StopReason.java \
        src/main/java/org/example/agent/model/ModelRequest.java \
        src/main/java/org/example/agent/model/ModelResponse.java \
        src/main/java/org/example/agent/model/ModelClient.java
git commit -m "feat(model): add ModelClient interface and request/response types"
```

---

### Task 7: tool — Tool, UnknownToolException, ToolRegistry

**Files:**
- Create: `src/main/java/org/example/agent/tool/Tool.java`
- Create: `src/main/java/org/example/agent/tool/UnknownToolException.java`
- Create: `src/main/java/org/example/agent/tool/ToolRegistry.java`
- Test: `src/test/java/org/example/agent/tool/ToolRegistryTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/example/agent/tool/ToolRegistryTest.java`:

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private Tool echoTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "Echoes input", Map.of());
            }
            @Override
            public String execute(Map<String, Object> input) {
                return String.valueOf(input.get("text"));
            }
        };
    }

    @Test
    void definitions_returns_registered_tool_definitions() {
        var registry = new ToolRegistry();
        registry.register(echoTool());
        assertEquals(1, registry.definitions().size());
        assertEquals("echo", registry.definitions().get(0).name());
    }

    @Test
    void execute_known_tool_returns_tool_result_block() {
        var registry = new ToolRegistry();
        registry.register(echoTool());

        var toolUse = new ContentBlock.ToolUse("call-1", "echo", Map.of("text", "hello"));
        var result = registry.execute(toolUse);

        assertEquals("call-1", result.toolUseId());
        assertEquals("hello", result.content());
    }

    @Test
    void execute_unknown_tool_throws_unknown_tool_exception() {
        var registry = new ToolRegistry();
        var toolUse = new ContentBlock.ToolUse("call-2", "nonexistent", Map.of());

        var ex = assertThrows(UnknownToolException.class, () -> registry.execute(toolUse));
        assertTrue(ex.getMessage().contains("nonexistent"));
    }

    @Test
    void register_multiple_tools_all_appear_in_definitions() {
        var registry = new ToolRegistry();
        registry.register(echoTool());
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("noop", "Does nothing", Map.of());
            }
            @Override
            public String execute(Map<String, Object> input) {
                return "ok";
            }
        });
        assertEquals(2, registry.definitions().size());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=ToolRegistryTest`
Expected: FAIL — compilation error (tool types not yet defined)

- [ ] **Step 3: Create Tool.java**

Create `src/main/java/org/example/agent/tool/Tool.java`:

```java
package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;

import java.util.Map;

public interface Tool {
    ToolDefinition definition();
    String execute(Map<String, Object> input);
}
```

- [ ] **Step 4: Create UnknownToolException.java**

Create `src/main/java/org/example/agent/tool/UnknownToolException.java`:

```java
package org.example.agent.tool;

public class UnknownToolException extends RuntimeException {
    public UnknownToolException(String toolName) {
        super("Unknown tool: " + toolName);
    }
}
```

- [ ] **Step 5: Create ToolRegistry.java**

Create `src/main/java/org/example/agent/tool/ToolRegistry.java`:

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        tools.put(tool.definition().name(), tool);
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    public ContentBlock.ToolResult execute(ContentBlock.ToolUse toolUse) {
        var tool = tools.get(toolUse.name());
        if (tool == null) {
            throw new UnknownToolException(toolUse.name());
        }
        var result = tool.execute(toolUse.input());
        return new ContentBlock.ToolResult(toolUse.id(), result);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn test -Dtest=ToolRegistryTest`
Expected: PASS — 4 tests, 0 failures

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/agent/tool/Tool.java \
        src/main/java/org/example/agent/tool/UnknownToolException.java \
        src/main/java/org/example/agent/tool/ToolRegistry.java \
        src/test/java/org/example/agent/tool/ToolRegistryTest.java
git commit -m "feat(tool): add Tool interface, ToolRegistry, UnknownToolException"
```

---

### Task 8: engine — QueryResult

**Files:**
- Create: `src/main/java/org/example/agent/engine/QueryResult.java`

Structural type — no logic. Create directly and verify compilation.

- [ ] **Step 1: Create QueryResult.java**

Create `src/main/java/org/example/agent/engine/QueryResult.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.Message;

import java.util.List;

public sealed interface QueryResult permits
        QueryResult.Success,
        QueryResult.Failed {

    record Success(List<Message> messages, int totalTurns) implements QueryResult {}

    record Failed(Throwable cause, List<Message> messages) implements QueryResult {}
}
```

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/agent/engine/QueryResult.java
git commit -m "feat(engine): add QueryResult sealed interface"
```

---

### Task 9: engine — QueryEngine

**Files:**
- Create: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Test: `src/test/java/org/example/agent/engine/QueryEngineTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/example/agent/engine/QueryEngineTest.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineTest {

    private QueryParams params(String userMessage) {
        return new QueryParams(
                List.of(Message.user(userMessage)),
                "You are helpful.",
                null, null, null
        );
    }

    // ---------------------------------------------------------------
    // END_TURN: model responds and finishes immediately
    // ---------------------------------------------------------------

    @Test
    void end_turn_returns_success_with_final_assistant_message() {
        var registry = new ToolRegistry();
        var callCount = new int[]{0};

        var engine = new QueryEngine(
                request -> {
                    callCount[0]++;
                    return new ModelResponse(
                            List.of(new ContentBlock.Text("Hello!")),
                            StopReason.END_TURN,
                            10, 5
                    );
                },
                registry
        );

        var result = engine.run(params("Hi"));

        assertInstanceOf(QueryResult.Success.class, result);
        var success = (QueryResult.Success) result;
        assertEquals(1, success.totalTurns());
        assertEquals(1, callCount[0]);
        // messages: original user + final assistant
        assertEquals(2, success.messages().size());
        assertEquals(Role.ASSISTANT, success.messages().get(1).role());
    }

    // ---------------------------------------------------------------
    // TOOL_USE: model calls a tool, engine executes it, continues
    // ---------------------------------------------------------------

    @Test
    void tool_use_executes_tool_appends_result_and_continues() {
        var toolUse = new ContentBlock.ToolUse("call-1", "greet", Map.of("name", "World"));

        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("greet", "Greet someone", Map.of());
            }
            @Override
            public String execute(Map<String, Object> input) {
                return "Hello, " + input.get("name") + "!";
            }
        });

        // Call 1: TOOL_USE  →  Call 2: END_TURN
        var responses = new ModelResponse[]{
                new ModelResponse(List.of(toolUse), StopReason.TOOL_USE, 10, 5),
                new ModelResponse(List.of(new ContentBlock.Text("Done.")), StopReason.END_TURN, 20, 5)
        };
        var idx = new int[]{0};

        var engine = new QueryEngine(request -> responses[idx[0]++], registry);
        var result = engine.run(params("Greet the world"));

        assertInstanceOf(QueryResult.Success.class, result);
        var success = (QueryResult.Success) result;
        assertEquals(2, success.totalTurns());
        assertEquals(2, idx[0]);
        // messages: user → assistant(tooluse) → user(toolresult) → assistant("Done.")
        assertEquals(4, success.messages().size());
        assertEquals(Role.USER, success.messages().get(2).role());
        assertInstanceOf(ContentBlock.ToolResult.class, success.messages().get(2).content().get(0));
        var toolResult = (ContentBlock.ToolResult) success.messages().get(2).content().get(0);
        assertEquals("Hello, World!", toolResult.content());
    }

    // ---------------------------------------------------------------
    // MAX_TOKENS: output truncated, engine injects continue prompt
    // ---------------------------------------------------------------

    @Test
    void max_tokens_appends_continue_prompt_and_resumes() {
        var registry = new ToolRegistry();

        // Call 1: MAX_TOKENS  →  Call 2: END_TURN
        var responses = new ModelResponse[]{
                new ModelResponse(List.of(new ContentBlock.Text("Part 1...")), StopReason.MAX_TOKENS, 10, 100),
                new ModelResponse(List.of(new ContentBlock.Text("...Part 2.")), StopReason.END_TURN, 10, 20)
        };
        var idx = new int[]{0};

        var engine = new QueryEngine(request -> responses[idx[0]++], registry);
        var result = engine.run(params("Write a long essay"));

        assertInstanceOf(QueryResult.Success.class, result);
        var success = (QueryResult.Success) result;
        assertEquals(2, success.totalTurns());
        // messages: user → assistant(part1) → user("Please continue.") → assistant(part2)
        assertEquals(4, success.messages().size());
        assertEquals(Role.USER, success.messages().get(2).role());
        var continueBlock = (ContentBlock.Text) success.messages().get(2).content().get(0);
        assertEquals("Please continue.", continueBlock.text());
    }

    // ---------------------------------------------------------------
    // STOP_SEQUENCE: treated as terminal, same as END_TURN
    // ---------------------------------------------------------------

    @Test
    void stop_sequence_returns_success_immediately() {
        var registry = new ToolRegistry();
        var engine = new QueryEngine(
                request -> new ModelResponse(
                        List.of(new ContentBlock.Text("Stopped.")),
                        StopReason.STOP_SEQUENCE,
                        5, 3
                ),
                registry
        );

        var result = engine.run(params("Go"));
        assertInstanceOf(QueryResult.Success.class, result);
        assertEquals(1, ((QueryResult.Success) result).totalTurns());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=QueryEngineTest`
Expected: FAIL — compilation error (QueryEngine not yet defined)

- [ ] **Step 3: Create QueryEngine.java**

Create `src/main/java/org/example/agent/engine/QueryEngine.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelClient;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.tool.ToolRegistry;

import java.util.List;

public class QueryEngine {

    private static final String CONTINUE_PROMPT = "Please continue.";

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
    }

    public QueryResult run(QueryParams params) {
        var state = QueryState.from(params);
        while (true) {
            var request = buildRequest(state, params);
            var response = modelClient.call(request);

            var transition = decide(state, response);
            if (transition == null) {
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                return new QueryResult.Success(state.messages(), state.turnCount());
            }
            advance(state, transition, response);
        }
    }

    // ---------------------------------------------------------------
    // decide: reads response, returns transition reason or null (terminal)
    // Never mutates state.
    // ---------------------------------------------------------------

    private TransitionReason decide(QueryState state, ModelResponse response) {
        return switch (response.stopReason()) {
            case END_TURN -> null;
            case TOOL_USE -> new TransitionReason.ToolResultContinuation(collectResults(response));
            case MAX_TOKENS -> new TransitionReason.MaxTokensRecovery(state.continuationCount() + 1);
            case STOP_SEQUENCE -> null;
        };
    }

    // ---------------------------------------------------------------
    // advance: mutates state based on transition reason.
    // Never calls the model. Compiler enforces exhaustive coverage.
    // ---------------------------------------------------------------

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
            case TransitionReason.CompactRetry c -> { /* s06 extension */ }
            case TransitionReason.TransportRetry r -> { /* s11 extension */ }
            case TransitionReason.StopHookContinuation h -> { /* s08 extension */ }
            case TransitionReason.BudgetContinuation b -> { /* budget extension */ }
        }
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private ModelRequest buildRequest(QueryState state, QueryParams params) {
        Integer maxTokens = state.maxOutputTokensOverride()
                .orElse(params.maxOutputTokensOverride());
        return new ModelRequest(
                state.messages(),
                params.systemPrompt(),
                toolRegistry.definitions(),
                maxTokens
        );
    }

    private List<ContentBlock.ToolResult> collectResults(ModelResponse response) {
        return response.content().stream()
                .filter(b -> b instanceof ContentBlock.ToolUse)
                .map(b -> toolRegistry.execute((ContentBlock.ToolUse) b))
                .toList();
    }

    private Message buildToolResultMessage(List<ContentBlock.ToolResult> results) {
        return new Message(Role.USER, List.copyOf(results));
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=QueryEngineTest`
Expected: PASS — 4 tests, 0 failures

- [ ] **Step 5: Run all tests**

Run: `mvn test`
Expected: BUILD SUCCESS — all tests pass (MessageTest, QueryStateTest, TransitionReasonTest, ToolRegistryTest, QueryEngineTest)

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/engine/QueryEngineTest.java
git commit -m "feat(engine): add QueryEngine main loop"
```
