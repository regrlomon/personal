# s05 Skill Loading Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a skill loading system that injects a lightweight skill directory into the system prompt and provides a `load_skill` tool for on-demand full-text retrieval.

**Architecture:** Three types (`SkillManifest`, `SkillDocument`, `SkillRegistry`) live in `tool/skill/`. `SkillRegistry.of(Path)` scans a `skills/` directory, parsing `---` frontmatter from each `SKILL.md`. `LoadSkillTool` wraps the registry as a standard `Tool`. `QueryEngine` gains a new public constructor accepting a `SkillRegistry`; `buildRequest()` prepends the skill directory to the system prompt when the registry is non-null and non-empty. Existing constructors and tests are unchanged.

**Tech Stack:** Java 21, JUnit 5, no mocking framework.

---

## File Map

| Action | Path |
|--------|------|
| Create | `src/main/java/org/example/agent/tool/skill/SkillManifest.java` |
| Create | `src/main/java/org/example/agent/tool/skill/SkillDocument.java` |
| Create | `src/main/java/org/example/agent/tool/skill/SkillRegistry.java` |
| Create | `src/main/java/org/example/agent/tool/skill/LoadSkillTool.java` |
| Modify | `src/main/java/org/example/agent/engine/QueryEngine.java` |
| Create | `src/main/resources/skills/code-review/SKILL.md` |
| Create | `src/main/resources/skills/git-workflow/SKILL.md` |
| Create | `src/test/java/org/example/agent/tool/skill/SkillRegistryTest.java` |
| Create | `src/test/java/org/example/agent/tool/skill/LoadSkillToolTest.java` |
| Create | `src/test/java/org/example/agent/engine/QueryEngineSkillTest.java` |

---

## Task 1: SkillManifest & SkillDocument

Plain records — no logic, no tests needed.

**Files:**
- Create: `src/main/java/org/example/agent/tool/skill/SkillManifest.java`
- Create: `src/main/java/org/example/agent/tool/skill/SkillDocument.java`

- [ ] **Step 1: Create SkillManifest**

```java
package org.example.agent.tool.skill;

public record SkillManifest(String name, String description) {}
```

- [ ] **Step 2: Create SkillDocument**

```java
package org.example.agent.tool.skill;

public record SkillDocument(SkillManifest manifest, String body) {}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/example/agent/tool/skill/
git commit -m "feat(skill): add SkillManifest and SkillDocument records"
```

---

## Task 2: SkillRegistry

Scans a directory for `SKILL.md` files, parses `---` frontmatter, exposes a skill directory string and body lookup.

**Files:**
- Create: `src/main/java/org/example/agent/tool/skill/SkillRegistry.java`
- Create: `src/test/java/org/example/agent/tool/skill/SkillRegistryTest.java`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/org/example/agent/tool/skill/SkillRegistryTest.java`:

```java
package org.example.agent.tool.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    @TempDir Path skillsDir;

    @Test
    void loads_skill_body_from_SKILL_md() throws IOException {
        writeSkillFile("code-review", "Code review checklist", "Step 1: Check logic.");
        var registry = SkillRegistry.of(skillsDir);
        assertEquals("Step 1: Check logic.", registry.loadBody("code-review"));
    }

    @Test
    void describe_available_lists_all_skills() throws IOException {
        writeSkillFile("alpha", "Alpha skill", "alpha body");
        writeSkillFile("beta", "Beta skill", "beta body");
        var registry = SkillRegistry.of(skillsDir);
        var desc = registry.describeAvailable();
        assertTrue(desc.startsWith("Skills available:"), "should start with header");
        assertTrue(desc.contains("- alpha: Alpha skill"), "should list alpha");
        assertTrue(desc.contains("- beta: Beta skill"), "should list beta");
    }

    @Test
    void empty_registry_returns_empty_description() {
        assertEquals("", SkillRegistry.empty().describeAvailable());
    }

    @Test
    void load_unknown_skill_throws_illegal_argument() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> SkillRegistry.empty().loadBody("no-such-skill"));
        assertTrue(ex.getMessage().contains("no-such-skill"));
    }

    private void writeSkillFile(String name, String description, String body) throws IOException {
        var dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn test -Dtest=SkillRegistryTest
```

Expected: compilation error — `SkillRegistry` does not exist yet.

- [ ] **Step 3: Create SkillRegistry**

Create `src/main/java/org/example/agent/tool/skill/SkillRegistry.java`:

```java
package org.example.agent.tool.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class SkillRegistry {

    private final Map<String, SkillDocument> skills;

    public SkillRegistry(Map<String, SkillDocument> skills) {
        this.skills = Map.copyOf(skills);
    }

    public static SkillRegistry of(Path skillsDir) {
        var skills = new LinkedHashMap<String, SkillDocument>();
        try (var stream = Files.walk(skillsDir, 2)) {
            stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                  .forEach(path -> {
                      try {
                          var doc = parse(Files.readString(path));
                          skills.put(doc.manifest().name(), doc);
                      } catch (IOException e) {
                          throw new UncheckedIOException(e);
                      }
                  });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new SkillRegistry(skills);
    }

    public static SkillRegistry empty() {
        return new SkillRegistry(Map.of());
    }

    public String describeAvailable() {
        if (skills.isEmpty()) return "";
        var sb = new StringBuilder("Skills available:");
        skills.values().forEach(doc ->
                sb.append("\n- ").append(doc.manifest().name())
                  .append(": ").append(doc.manifest().description()));
        return sb.toString();
    }

    public String loadBody(String name) {
        var doc = skills.get(name);
        if (doc == null) throw new IllegalArgumentException("unknown skill: " + name);
        return doc.body();
    }

    private static SkillDocument parse(String raw) {
        var text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            throw new IllegalArgumentException("SKILL.md must start with '---'");
        }
        var parts = text.split("---\n", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("SKILL.md frontmatter not closed");
        }
        String name = null, description = "";
        for (var line : parts[1].lines().toList()) {
            var idx = line.indexOf(':');
            if (idx < 0) continue;
            var key = line.substring(0, idx).trim();
            var value = line.substring(idx + 1).trim();
            if ("name".equals(key)) name = value;
            else if ("description".equals(key)) description = value;
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("SKILL.md missing 'name' in frontmatter");
        }
        return new SkillDocument(new SkillManifest(name, description), parts[2].strip());
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
mvn test -Dtest=SkillRegistryTest
```

Expected: 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/skill/SkillRegistry.java \
        src/test/java/org/example/agent/tool/skill/SkillRegistryTest.java
git commit -m "feat(skill): implement SkillRegistry with frontmatter parsing"
```

---

## Task 3: LoadSkillTool

**Files:**
- Create: `src/main/java/org/example/agent/tool/skill/LoadSkillTool.java`
- Create: `src/test/java/org/example/agent/tool/skill/LoadSkillToolTest.java`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/org/example/agent/tool/skill/LoadSkillToolTest.java`:

```java
package org.example.agent.tool.skill;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoadSkillToolTest {

    private static SkillRegistry registryWith(String name, String description, String body) {
        return new SkillRegistry(Map.of(name,
                new SkillDocument(new SkillManifest(name, description), body)));
    }

    @Test
    void returns_skill_body_wrapped_in_xml_tag() {
        var tool = new LoadSkillTool(registryWith("foo", "Foo skill", "foo body content"));
        var result = tool.execute(Map.of("name", "foo"), null);
        assertTrue(result.ok());
        assertTrue(result.content().contains("<skill name=\"foo\">"), "should have opening tag");
        assertTrue(result.content().contains("foo body content"), "should include body");
        assertTrue(result.content().contains("</skill>"), "should have closing tag");
    }

    @Test
    void returns_error_for_unknown_skill() {
        var tool = new LoadSkillTool(SkillRegistry.empty());
        var result = tool.execute(Map.of("name", "unknown"), null);
        assertFalse(result.ok());
        assertTrue(result.isError());
        assertTrue(result.content().contains("unknown"));
    }

    @Test
    void returns_error_for_blank_name() {
        var tool = new LoadSkillTool(SkillRegistry.empty());
        var result = tool.execute(Map.of("name", "  "), null);
        assertFalse(result.ok());
    }

    @Test
    void returns_error_when_name_key_missing() {
        var tool = new LoadSkillTool(SkillRegistry.empty());
        var result = tool.execute(Map.of(), null);
        assertFalse(result.ok());
    }

    @Test
    void definition_name_is_load_skill() {
        assertEquals("load_skill", new LoadSkillTool(SkillRegistry.empty()).definition().name());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn test -Dtest=LoadSkillToolTest
```

Expected: compilation error — `LoadSkillTool` does not exist yet.

- [ ] **Step 3: Create LoadSkillTool**

Create `src/main/java/org/example/agent/tool/skill/LoadSkillTool.java`:

```java
package org.example.agent.tool.skill;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

public class LoadSkillTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "load_skill",
            "Load the full content of a skill by name.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string", "description", "The skill name to load")
                    ),
                    "required", List.of("name")
            )
    );

    private final SkillRegistry registry;

    public LoadSkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var raw = input.get("name");
        if (!(raw instanceof String name) || name.isBlank()) {
            return ToolResultEnvelope.error("name must be a non-blank string");
        }
        try {
            var body = registry.loadBody(name);
            return ToolResultEnvelope.success(
                    "<skill name=\"" + name + "\">\n" + body + "\n</skill>");
        } catch (IllegalArgumentException e) {
            return ToolResultEnvelope.error(e.getMessage());
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
mvn test -Dtest=LoadSkillToolTest
```

Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/tool/skill/LoadSkillTool.java \
        src/test/java/org/example/agent/tool/skill/LoadSkillToolTest.java
git commit -m "feat(skill): implement LoadSkillTool"
```

---

## Task 4: QueryEngine — SkillRegistry integration

Adds a new public constructor and a `augmentSystemPrompt()` helper. The existing two constructors delegate to a new private 4-arg constructor, leaving their signatures unchanged.

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Create: `src/test/java/org/example/agent/engine/QueryEngineSkillTest.java`

- [ ] **Step 1: Write failing tests**

Create `src/test/java/org/example/agent/engine/QueryEngineSkillTest.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.ToolRegistry;
import org.example.agent.tool.skill.SkillDocument;
import org.example.agent.tool.skill.SkillManifest;
import org.example.agent.tool.skill.SkillRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineSkillTest {

    private static ModelResponse endTurn() {
        return new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 0, 0);
    }

    private static QueryParams params(String systemPrompt) {
        return new QueryParams(List.of(Message.user("hi")), systemPrompt, null, null, null);
    }

    @Test
    void skill_directory_is_prepended_to_system_prompt() {
        var doc = new SkillDocument(new SkillManifest("foo", "Foo skill"), "foo body");
        var skillReg = new SkillRegistry(Map.of("foo", doc));
        var captured = new ArrayList<ModelRequest>();
        var engine = new QueryEngine(req -> { captured.add(req); return endTurn(); },
                new ToolRegistry(), skillReg);

        engine.run(params("base prompt"));

        assertEquals(1, captured.size());
        var sp = captured.get(0).systemPrompt();
        assertTrue(sp.startsWith("Skills available:"), "should start with skill section");
        assertTrue(sp.contains("foo: Foo skill"), "should list skill");
        assertTrue(sp.contains("base prompt"), "should retain base prompt");
    }

    @Test
    void null_system_prompt_produces_skill_section_only() {
        var doc = new SkillDocument(new SkillManifest("bar", "Bar skill"), "bar body");
        var skillReg = new SkillRegistry(Map.of("bar", doc));
        var captured = new ArrayList<ModelRequest>();
        var engine = new QueryEngine(req -> { captured.add(req); return endTurn(); },
                new ToolRegistry(), skillReg);

        engine.run(params(null));

        var sp = captured.get(0).systemPrompt();
        assertNotNull(sp);
        assertTrue(sp.contains("bar: Bar skill"));
        assertFalse(sp.contains("null"), "must not contain literal 'null'");
    }

    @Test
    void empty_registry_leaves_system_prompt_unchanged() {
        var captured = new ArrayList<ModelRequest>();
        var engine = new QueryEngine(req -> { captured.add(req); return endTurn(); },
                new ToolRegistry(), SkillRegistry.empty());

        engine.run(params("original prompt"));

        assertEquals("original prompt", captured.get(0).systemPrompt());
    }

    @Test
    void no_skill_registry_leaves_system_prompt_unchanged() {
        var captured = new ArrayList<ModelRequest>();
        var engine = new QueryEngine(req -> { captured.add(req); return endTurn(); },
                new ToolRegistry());

        engine.run(params("original prompt"));

        assertEquals("original prompt", captured.get(0).systemPrompt());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn test -Dtest=QueryEngineSkillTest
```

Expected: compilation error — `QueryEngine` lacks the 3-arg constructor with `SkillRegistry`.

- [ ] **Step 3: Modify QueryEngine**

Replace the full file content of `src/main/java/org/example/agent/engine/QueryEngine.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelClient;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.*;
import org.example.agent.tool.skill.SkillRegistry;

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
    private final SkillRegistry skillRegistry; // nullable

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this(modelClient, toolRegistry, null, ForkJoinPool.commonPool());
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, SkillRegistry skillRegistry) {
        this(modelClient, toolRegistry, skillRegistry, ForkJoinPool.commonPool());
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, ExecutorService executor) {
        this(modelClient, toolRegistry, null, executor);
    }

    private QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                        SkillRegistry skillRegistry, ExecutorService executor) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        var router = new ToolRouter(toolRegistry);
        this.runtime = new ToolExecutionRuntime(router, executor);
    }

    public QueryResult run(QueryParams params) {
        var state = QueryState.from(params);
        var ctx = ToolUseContext.defaults(System.getProperty("user.dir"));
        while (true) {
            if (params.maxTurns() != null && state.turnCount() > params.maxTurns()) {
                return new QueryResult.Success(state.messages(), state.turnCount());
            }
            var response = modelClient.call(buildRequest(state, params));

            if (response.stopReason() == StopReason.TOOL_USE) {
                var toolUses = response.content().stream()
                        .filter(b -> b instanceof ContentBlock.ToolUse)
                        .map(b -> (ContentBlock.ToolUse) b)
                        .toList();
                if (toolUses.isEmpty()) {
                    state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                    return new QueryResult.Success(state.messages(), state.turnCount());
                }
                ctx.planningState().tickRound();
                var execResult = runtime.execute(toolUses, ctx);
                ctx = execResult.updatedContext();
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                state.appendMessage(buildToolResultMessage(execResult.toolResults(), ctx.planningState().needsReminder()));
                state.setLastTransition(new TransitionReason.ToolResultContinuation(execResult.toolResults()));
                state.incrementTurn();
            } else {
                var transition = decide(state, response);
                if (transition == null) {
                    state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                    return new QueryResult.Success(state.messages(), state.turnCount());
                }
                advance(state, transition, response);
            }
        }
    }

    private TransitionReason decide(QueryState state, ModelResponse response) {
        return switch (response.stopReason()) {
            case END_TURN -> null;
            case TOOL_USE -> throw new IllegalStateException("TOOL_USE handled in run()");
            case MAX_TOKENS -> new TransitionReason.MaxTokensRecovery(state.continuationCount() + 1);
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
            case TransitionReason.CompactRetry c -> { /* s06 extension */ }
            case TransitionReason.TransportRetry r -> { /* s11 extension */ }
            case TransitionReason.StopHookContinuation h -> { /* s08 extension */ }
            case TransitionReason.BudgetContinuation b -> { /* budget extension */ }
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

    private Message buildToolResultMessage(List<ContentBlock.ToolResult> results, boolean prependReminder) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (prependReminder) {
            blocks.add(new ContentBlock.Text(REMINDER_TEXT));
        }
        blocks.addAll(results);
        return new Message(Role.USER, List.copyOf(blocks));
    }
}
```

- [ ] **Step 4: Run the full test suite**

```
mvn test
```

Expected: all existing tests PASS, 4 new `QueryEngineSkillTest` tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/engine/QueryEngineSkillTest.java
git commit -m "feat(engine): integrate SkillRegistry into QueryEngine"
```

---

## Task 5: Sample SKILL.md files

Two example skills in `src/main/resources/skills/` for demonstration purposes.

**Files:**
- Create: `src/main/resources/skills/code-review/SKILL.md`
- Create: `src/main/resources/skills/git-workflow/SKILL.md`

- [ ] **Step 1: Create code-review skill**

Create `src/main/resources/skills/code-review/SKILL.md`:

```markdown
---
name: code-review
description: Checklist for reviewing code changes
---
## Code Review Checklist

1. Does the change have tests?
2. Are edge cases handled?
3. Is error handling appropriate for the failure modes that can actually occur?
4. Are names clear and consistent with the rest of the codebase?
5. Does the change introduce unnecessary complexity?
```

- [ ] **Step 2: Create git-workflow skill**

Create `src/main/resources/skills/git-workflow/SKILL.md`:

```markdown
---
name: git-workflow
description: Branch and commit guidance
---
## Git Workflow

- Branch from `master`; name branches `feat/`, `fix/`, `docs/` by type.
- Commit messages follow Conventional Commits: `type(scope): subject`.
- Keep commits small; each commit should pass tests independently.
- Squash fixup commits before merging.
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/skills/
git commit -m "docs(skill): add sample code-review and git-workflow skills"
```
