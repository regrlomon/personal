# Permission System (s07) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a 4-step permission pipeline (deny → mode → allow → ask) that gates every tool call before execution, supporting three modes (DEFAULT / PLAN / AUTO) and special Bash safety checks.

**Architecture:** A new `permission` package holds the data structures and `PermissionChecker`. `ToolUseContext` carries a nullable `PermissionChecker` + `UserConfirmation`; `ToolRouter.routeToEnvelope()` reads them and enforces the pipeline before calling `tool.execute()`. Everything is opt-in — if no checker is wired, behaviour is identical to pre-s07.

**Tech Stack:** Java 21 records / sealed interfaces, JUnit Jupiter 5 (no mocking library).

---

## File Structure

**New files:**
- `src/main/java/org/example/agent/permission/PermissionBehavior.java` — `ALLOW / DENY / ASK` enum
- `src/main/java/org/example/agent/permission/PermissionMode.java` — `DEFAULT / PLAN / AUTO` enum
- `src/main/java/org/example/agent/permission/PermissionRule.java` — rule record (tool, behavior, content)
- `src/main/java/org/example/agent/permission/PermissionDecision.java` — result record (behavior, reason)
- `src/main/java/org/example/agent/permission/UserConfirmation.java` — `@FunctionalInterface` for user prompt
- `src/main/java/org/example/agent/permission/PermissionChecker.java` — 4-step pipeline class

**New test files:**
- `src/test/java/org/example/agent/permission/PermissionCheckerTest.java`
- `src/test/java/org/example/agent/tool/ToolRouterPermissionTest.java`

**Modified files:**
- `src/main/java/org/example/agent/tool/ToolUseContext.java` — add `permissionChecker` + `userConfirmation` fields + `withPermissions()` factory
- `src/main/java/org/example/agent/tool/ToolRouter.java` — enforce permission in `routeToEnvelope()`
- `src/main/java/org/example/agent/engine/QueryEngine.java` — new constructor accepting `PermissionChecker + UserConfirmation`

---

### Task 1: Core enums — PermissionBehavior and PermissionMode

**Files:**
- Create: `src/main/java/org/example/agent/permission/PermissionBehavior.java`
- Create: `src/main/java/org/example/agent/permission/PermissionMode.java`
- Test: `src/test/java/org/example/agent/permission/PermissionCheckerTest.java` (start file here)

- [ ] **Step 1: Write the failing test**

Create `src/test/java/org/example/agent/permission/PermissionCheckerTest.java`:

```java
package org.example.agent.permission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PermissionCheckerTest {

    @Test
    void permission_behavior_has_three_values() {
        assertEquals(3, PermissionBehavior.values().length);
        assertNotNull(PermissionBehavior.ALLOW);
        assertNotNull(PermissionBehavior.DENY);
        assertNotNull(PermissionBehavior.ASK);
    }

    @Test
    void permission_mode_has_three_values() {
        assertEquals(3, PermissionMode.values().length);
        assertNotNull(PermissionMode.DEFAULT);
        assertNotNull(PermissionMode.PLAN);
        assertNotNull(PermissionMode.AUTO);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
mvn test -pl . -Dtest=PermissionCheckerTest#permission_behavior_has_three_values -q
```
Expected: FAIL with `PermissionBehavior cannot be resolved`

- [ ] **Step 3: Implement PermissionBehavior**

Create `src/main/java/org/example/agent/permission/PermissionBehavior.java`:

```java
package org.example.agent.permission;

public enum PermissionBehavior {
    ALLOW, DENY, ASK
}
```

- [ ] **Step 4: Implement PermissionMode**

Create `src/main/java/org/example/agent/permission/PermissionMode.java`:

```java
package org.example.agent.permission;

public enum PermissionMode {
    DEFAULT, PLAN, AUTO
}
```

- [ ] **Step 5: Run tests to verify they pass**

```
mvn test -pl . -Dtest=PermissionCheckerTest#permission_behavior_has_three_values+permission_mode_has_three_values -q
```
Expected: BUILD SUCCESS, 2 tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/permission/PermissionBehavior.java \
        src/main/java/org/example/agent/permission/PermissionMode.java \
        src/test/java/org/example/agent/permission/PermissionCheckerTest.java
git commit -m "feat(permission): add PermissionBehavior and PermissionMode enums"
```

---

### Task 2: Data structures — PermissionRule, PermissionDecision, UserConfirmation

**Files:**
- Create: `src/main/java/org/example/agent/permission/PermissionRule.java`
- Create: `src/main/java/org/example/agent/permission/PermissionDecision.java`
- Create: `src/main/java/org/example/agent/permission/UserConfirmation.java`
- Test: `src/test/java/org/example/agent/permission/PermissionCheckerTest.java` (add tests)

- [ ] **Step 1: Write the failing tests** — add to `PermissionCheckerTest.java`:

```java
    @Test
    void permission_rule_stores_fields() {
        var rule = new PermissionRule("bash", PermissionBehavior.DENY, "sudo *");
        assertEquals("bash", rule.tool());
        assertEquals(PermissionBehavior.DENY, rule.behavior());
        assertEquals("sudo *", rule.content());
    }

    @Test
    void permission_rule_allows_null_content() {
        var rule = new PermissionRule("read_file", PermissionBehavior.ALLOW, null);
        assertNull(rule.content());
    }

    @Test
    void permission_decision_stores_behavior_and_reason() {
        var decision = new PermissionDecision(PermissionBehavior.ALLOW, "matched allow rule");
        assertEquals(PermissionBehavior.ALLOW, decision.behavior());
        assertEquals("matched allow rule", decision.reason());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn test -pl . -Dtest=PermissionCheckerTest -q 2>&1 | tail -5
```
Expected: FAIL — `PermissionRule cannot be resolved`

- [ ] **Step 3: Implement PermissionRule**

Create `src/main/java/org/example/agent/permission/PermissionRule.java`:

```java
package org.example.agent.permission;

public record PermissionRule(String tool, PermissionBehavior behavior, String content) {}
```

- [ ] **Step 4: Implement PermissionDecision**

Create `src/main/java/org/example/agent/permission/PermissionDecision.java`:

```java
package org.example.agent.permission;

public record PermissionDecision(PermissionBehavior behavior, String reason) {}
```

- [ ] **Step 5: Implement UserConfirmation**

Create `src/main/java/org/example/agent/permission/UserConfirmation.java`:

```java
package org.example.agent.permission;

import java.util.Map;

@FunctionalInterface
public interface UserConfirmation {
    boolean confirm(String toolName, Map<String, Object> input, String reason);

    static UserConfirmation alwaysAllow() { return (t, i, r) -> true; }
    static UserConfirmation alwaysDeny()  { return (t, i, r) -> false; }
}
```

- [ ] **Step 6: Run tests to verify they pass**

```
mvn test -pl . -Dtest=PermissionCheckerTest -q
```
Expected: BUILD SUCCESS, 5 tests pass

- [ ] **Step 7: Commit**

```bash
git add src/main/java/org/example/agent/permission/PermissionRule.java \
        src/main/java/org/example/agent/permission/PermissionDecision.java \
        src/main/java/org/example/agent/permission/UserConfirmation.java \
        src/test/java/org/example/agent/permission/PermissionCheckerTest.java
git commit -m "feat(permission): add PermissionRule, PermissionDecision, UserConfirmation"
```

---

### Task 3: PermissionChecker — deny rules + mode check

**Files:**
- Create: `src/main/java/org/example/agent/permission/PermissionChecker.java`
- Test: `src/test/java/org/example/agent/permission/PermissionCheckerTest.java` (add tests)

- [ ] **Step 1: Write the failing tests** — add to `PermissionCheckerTest.java`:

```java
    // ---- deny rules ----

    @Test
    void deny_rule_with_matching_content_returns_deny() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT)
                .addDenyRule(new PermissionRule("bash", PermissionBehavior.DENY, "sudo"));
        var decision = checker.check("bash", Map.of("command", "sudo rm -rf /"));
        assertEquals(PermissionBehavior.DENY, decision.behavior());
    }

    @Test
    void deny_rule_for_wrong_tool_does_not_match() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT)
                .addDenyRule(new PermissionRule("bash", PermissionBehavior.DENY, "sudo"));
        var decision = checker.check("read_file", Map.of("path", "sudo_notes.txt"));
        // no deny rule matched → fallback ASK
        assertEquals(PermissionBehavior.ASK, decision.behavior());
    }

    @Test
    void deny_rule_without_content_matches_any_input_for_that_tool() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT)
                .addDenyRule(new PermissionRule("bash", PermissionBehavior.DENY, null));
        var decision = checker.check("bash", Map.of("command", "echo hello"));
        assertEquals(PermissionBehavior.DENY, decision.behavior());
    }

    // ---- mode: PLAN ----

    @Test
    void plan_mode_denies_bash() {
        var checker = new PermissionChecker(PermissionMode.PLAN);
        var decision = checker.check("bash", Map.of("command", "echo hello"));
        assertEquals(PermissionBehavior.DENY, decision.behavior());
        assertTrue(decision.reason().contains("plan mode"));
    }

    @Test
    void plan_mode_denies_write_file() {
        var checker = new PermissionChecker(PermissionMode.PLAN);
        var decision = checker.check("write_file", Map.of("path", "out.txt", "content", "x"));
        assertEquals(PermissionBehavior.DENY, decision.behavior());
    }

    @Test
    void plan_mode_denies_edit_file() {
        var checker = new PermissionChecker(PermissionMode.PLAN);
        var decision = checker.check("edit_file", Map.of("path", "file.txt"));
        assertEquals(PermissionBehavior.DENY, decision.behavior());
    }

    @Test
    void plan_mode_allows_read_file_to_reach_ask() {
        var checker = new PermissionChecker(PermissionMode.PLAN);
        var decision = checker.check("read_file", Map.of("path", "README.md"));
        // not denied by plan mode — falls through to ASK (no allow rules configured)
        assertEquals(PermissionBehavior.ASK, decision.behavior());
    }

    // ---- mode: AUTO ----

    @Test
    void auto_mode_allows_read_file_without_rules() {
        var checker = new PermissionChecker(PermissionMode.AUTO);
        var decision = checker.check("read_file", Map.of("path", "README.md"));
        assertEquals(PermissionBehavior.ALLOW, decision.behavior());
        assertTrue(decision.reason().contains("auto mode"));
    }

    @Test
    void auto_mode_does_not_auto_allow_bash() {
        var checker = new PermissionChecker(PermissionMode.AUTO);
        var decision = checker.check("bash", Map.of("command", "echo hello"));
        // not in READ_ONLY set → falls through to ASK
        assertEquals(PermissionBehavior.ASK, decision.behavior());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn test -pl . -Dtest=PermissionCheckerTest -q 2>&1 | tail -5
```
Expected: FAIL — `PermissionChecker cannot be resolved`

- [ ] **Step 3: Implement PermissionChecker (deny rules + mode check)**

Create `src/main/java/org/example/agent/permission/PermissionChecker.java`:

```java
package org.example.agent.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PermissionChecker {

    private static final Set<String> WRITE_TOOLS = Set.of("bash", "write_file", "edit_file");
    private static final Set<String> READ_ONLY_TOOLS = Set.of("read_file");

    private PermissionMode mode;
    private final List<PermissionRule> denyRules  = new ArrayList<>();
    private final List<PermissionRule> allowRules = new ArrayList<>();
    private int denialCount = 0;

    public PermissionChecker(PermissionMode mode) {
        this.mode = mode;
    }

    public PermissionChecker addDenyRule(PermissionRule rule) {
        denyRules.add(rule);
        return this;
    }

    public PermissionChecker addAllowRule(PermissionRule rule) {
        allowRules.add(rule);
        return this;
    }

    public PermissionDecision check(String toolName, Map<String, Object> input) {
        // Step 1: explicit deny rules
        for (var rule : denyRules) {
            if (ruleMatches(rule, toolName, input)) {
                denialCount++;
                return new PermissionDecision(PermissionBehavior.DENY,
                        "matched deny rule" + (rule.content() != null ? ": " + rule.content() : ""));
            }
        }

        // Step 2: mode check
        if (mode == PermissionMode.PLAN && WRITE_TOOLS.contains(toolName)) {
            denialCount++;
            return new PermissionDecision(PermissionBehavior.DENY,
                    "plan mode blocks write tool: " + toolName);
        }
        if (mode == PermissionMode.AUTO && READ_ONLY_TOOLS.contains(toolName)) {
            return new PermissionDecision(PermissionBehavior.ALLOW,
                    "auto mode allows read-only tool: " + toolName);
        }

        // Step 3: explicit allow rules
        for (var rule : allowRules) {
            if (ruleMatches(rule, toolName, input)) {
                return new PermissionDecision(PermissionBehavior.ALLOW, "matched allow rule");
            }
        }

        // Step 4: fallback — ask user
        return new PermissionDecision(PermissionBehavior.ASK, "needs user confirmation");
    }

    public int denialCount() { return denialCount; }

    public PermissionMode mode() { return mode; }

    public void setMode(PermissionMode mode) { this.mode = mode; }

    private boolean ruleMatches(PermissionRule rule, String toolName, Map<String, Object> input) {
        if (!rule.tool().equals(toolName)) return false;
        if (rule.content() == null) return true;
        var haystack = extractContent(input);
        return haystack.contains(rule.content().replace("*", "").strip());
    }

    private String extractContent(Map<String, Object> input) {
        return input.values().stream()
                .filter(v -> v instanceof String)
                .map(Object::toString)
                .collect(Collectors.joining(" "));
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
mvn test -pl . -Dtest=PermissionCheckerTest -q
```
Expected: BUILD SUCCESS, all tests pass (including Tasks 1-2)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/permission/PermissionChecker.java \
        src/test/java/org/example/agent/permission/PermissionCheckerTest.java
git commit -m "feat(permission): implement PermissionChecker with deny rules and mode check"
```

---

### Task 4: PermissionChecker — allow rules + fallback + Bash safety

**Files:**
- Modify: `src/main/java/org/example/agent/permission/PermissionChecker.java`
- Test: `src/test/java/org/example/agent/permission/PermissionCheckerTest.java` (add tests)

- [ ] **Step 1: Write the failing tests** — add to `PermissionCheckerTest.java`:

```java
    // ---- allow rules ----

    @Test
    void allow_rule_matching_tool_name_returns_allow() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT)
                .addAllowRule(new PermissionRule("read_file", PermissionBehavior.ALLOW, null));
        var decision = checker.check("read_file", Map.of("path", "README.md"));
        assertEquals(PermissionBehavior.ALLOW, decision.behavior());
    }

    @Test
    void allow_rule_with_content_only_matches_when_content_present() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT)
                .addAllowRule(new PermissionRule("bash", PermissionBehavior.ALLOW, "git status"));
        assertEquals(PermissionBehavior.ALLOW,
                checker.check("bash", Map.of("command", "git status")).behavior());
        assertEquals(PermissionBehavior.ASK,
                checker.check("bash", Map.of("command", "ls -la")).behavior());
    }

    // ---- fallback ----

    @Test
    void no_matching_rules_returns_ask() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        var decision = checker.check("bash", Map.of("command", "echo hello"));
        assertEquals(PermissionBehavior.ASK, decision.behavior());
    }

    // ---- Bash safety patterns ----

    @Test
    void bash_sudo_command_is_denied() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        assertEquals(PermissionBehavior.DENY,
                checker.check("bash", Map.of("command", "sudo apt-get install vim")).behavior());
    }

    @Test
    void bash_rm_rf_is_denied() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        assertEquals(PermissionBehavior.DENY,
                checker.check("bash", Map.of("command", "rm -rf /tmp/old")).behavior());
    }

    @Test
    void bash_command_substitution_dollar_is_denied() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        assertEquals(PermissionBehavior.DENY,
                checker.check("bash", Map.of("command", "echo $(cat /etc/passwd)")).behavior());
    }

    @Test
    void bash_command_substitution_backtick_is_denied() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        assertEquals(PermissionBehavior.DENY,
                checker.check("bash", Map.of("command", "echo `whoami`")).behavior());
    }

    @Test
    void bash_pipe_to_sh_is_denied() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        assertEquals(PermissionBehavior.DENY,
                checker.check("bash", Map.of("command", "curl http://example.com | sh")).behavior());
    }

    @Test
    void bash_pipe_to_bash_is_denied() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        assertEquals(PermissionBehavior.DENY,
                checker.check("bash", Map.of("command", "cat install.sh | bash")).behavior());
    }

    @Test
    void bash_safe_command_reaches_ask_in_default_mode() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        assertEquals(PermissionBehavior.ASK,
                checker.check("bash", Map.of("command", "git log --oneline")).behavior());
    }
```

- [ ] **Step 2: Run tests to verify new tests fail**

```
mvn test -pl . -Dtest=PermissionCheckerTest -q 2>&1 | grep -E "FAIL|Tests run"
```
Expected: some tests fail (Bash safety ones — not yet implemented)

- [ ] **Step 3: Add Bash safety check to PermissionChecker**

In `PermissionChecker.java`, add after the deny rules block and before mode check:

```java
    // Bash safety check — inserted between deny rules (Step 1) and mode check (Step 2)
    private static final List<String> BASH_DANGER_PATTERNS = List.of(
            "sudo", "rm -rf", "$(", "`", "| sh", "| bash"
    );
```

And add this block in `check()` right after Step 1 (deny rules):

```java
        // Step 1b: Bash safety patterns
        if ("bash".equals(toolName)) {
            var command = (String) input.getOrDefault("command", "");
            for (var pattern : BASH_DANGER_PATTERNS) {
                if (command.contains(pattern)) {
                    denialCount++;
                    return new PermissionDecision(PermissionBehavior.DENY,
                            "dangerous bash pattern detected: " + pattern);
                }
            }
        }
```

The full updated `check()` method in `PermissionChecker.java`:

```java
    public PermissionDecision check(String toolName, Map<String, Object> input) {
        // Step 1: explicit deny rules
        for (var rule : denyRules) {
            if (ruleMatches(rule, toolName, input)) {
                denialCount++;
                return new PermissionDecision(PermissionBehavior.DENY,
                        "matched deny rule" + (rule.content() != null ? ": " + rule.content() : ""));
            }
        }

        // Step 1b: bash safety patterns
        if ("bash".equals(toolName)) {
            var command = (String) input.getOrDefault("command", "");
            for (var pattern : BASH_DANGER_PATTERNS) {
                if (command.contains(pattern)) {
                    denialCount++;
                    return new PermissionDecision(PermissionBehavior.DENY,
                            "dangerous bash pattern detected: " + pattern);
                }
            }
        }

        // Step 2: mode check
        if (mode == PermissionMode.PLAN && WRITE_TOOLS.contains(toolName)) {
            denialCount++;
            return new PermissionDecision(PermissionBehavior.DENY,
                    "plan mode blocks write tool: " + toolName);
        }
        if (mode == PermissionMode.AUTO && READ_ONLY_TOOLS.contains(toolName)) {
            return new PermissionDecision(PermissionBehavior.ALLOW,
                    "auto mode allows read-only tool: " + toolName);
        }

        // Step 3: explicit allow rules
        for (var rule : allowRules) {
            if (ruleMatches(rule, toolName, input)) {
                return new PermissionDecision(PermissionBehavior.ALLOW, "matched allow rule");
            }
        }

        // Step 4: fallback — ask user
        return new PermissionDecision(PermissionBehavior.ASK, "needs user confirmation");
    }
```

- [ ] **Step 4: Run all PermissionCheckerTest tests**

```
mvn test -pl . -Dtest=PermissionCheckerTest -q
```
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/org/example/agent/permission/PermissionChecker.java \
        src/test/java/org/example/agent/permission/PermissionCheckerTest.java
git commit -m "feat(permission): add allow rules, fallback ask, and Bash safety patterns"
```

---

### Task 5: Denial counter

**Files:**
- Test: `src/test/java/org/example/agent/permission/PermissionCheckerTest.java` (add tests)
- `PermissionChecker.java` already has `denialCount` field — verify the tests pass with no code change

- [ ] **Step 1: Write the tests** — add to `PermissionCheckerTest.java`:

```java
    // ---- denial counter ----

    @Test
    void denial_count_starts_at_zero() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        assertEquals(0, checker.denialCount());
    }

    @Test
    void denial_count_increments_on_deny_rule_match() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT)
                .addDenyRule(new PermissionRule("bash", PermissionBehavior.DENY, null));
        checker.check("bash", Map.of("command", "echo hi"));
        checker.check("bash", Map.of("command", "echo hi"));
        assertEquals(2, checker.denialCount());
    }

    @Test
    void denial_count_increments_on_plan_mode_deny() {
        var checker = new PermissionChecker(PermissionMode.PLAN);
        checker.check("bash", Map.of("command", "echo hi"));
        assertEquals(1, checker.denialCount());
    }

    @Test
    void denial_count_does_not_increment_on_allow() {
        var checker = new PermissionChecker(PermissionMode.AUTO);
        checker.check("read_file", Map.of("path", "README.md"));
        assertEquals(0, checker.denialCount());
    }

    @Test
    void denial_count_increments_on_bash_safety_deny() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        checker.check("bash", Map.of("command", "sudo rm something"));
        assertEquals(1, checker.denialCount());
    }
```

- [ ] **Step 2: Run tests to verify they pass (no code change needed)**

```
mvn test -pl . -Dtest=PermissionCheckerTest -q
```
Expected: BUILD SUCCESS, all tests pass (counter was already implemented in Task 3)

- [ ] **Step 3: Commit**

```bash
git add src/test/java/org/example/agent/permission/PermissionCheckerTest.java
git commit -m "test(permission): add denial counter tests"
```

---

### Task 6: Add permission support to ToolUseContext

**Files:**
- Modify: `src/main/java/org/example/agent/tool/ToolUseContext.java`
- Test: create `src/test/java/org/example/agent/tool/ToolRouterPermissionTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/org/example/agent/tool/ToolRouterPermissionTest.java`:

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;
import org.example.agent.permission.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRouterPermissionTest {

    private ToolRegistry registryWithEcho() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "Echoes input", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("ok:" + input.get("text"));
            }
        });
        return registry;
    }

    @Test
    void no_permission_checker_allows_all_tools() {
        var ctx = ToolUseContext.defaults(".");
        var router = new ToolRouter(registryWithEcho());
        var result = router.route(new ContentBlock.ToolUse("1", "echo", Map.of("text", "hello")), ctx);
        assertEquals("ok:hello", result.content());
    }

    @Test
    void deny_decision_returns_error_envelope_without_executing_tool() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT)
                .addDenyRule(new PermissionRule("echo", PermissionBehavior.DENY, null));
        var ctx = ToolUseContext.defaults(".").withPermissions(checker, UserConfirmation.alwaysAllow());
        var router = new ToolRouter(registryWithEcho());
        var result = router.route(new ContentBlock.ToolUse("2", "echo", Map.of("text", "hello")), ctx);
        assertTrue(result.content().startsWith("Permission denied"));
    }

    @Test
    void ask_decision_with_user_allowing_executes_tool() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT); // no rules → ASK fallback
        var ctx = ToolUseContext.defaults(".").withPermissions(checker, UserConfirmation.alwaysAllow());
        var router = new ToolRouter(registryWithEcho());
        var result = router.route(new ContentBlock.ToolUse("3", "echo", Map.of("text", "hi")), ctx);
        assertEquals("ok:hi", result.content());
    }

    @Test
    void ask_decision_with_user_denying_returns_error_envelope() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT); // no rules → ASK fallback
        var ctx = ToolUseContext.defaults(".").withPermissions(checker, UserConfirmation.alwaysDeny());
        var router = new ToolRouter(registryWithEcho());
        var result = router.route(new ContentBlock.ToolUse("4", "echo", Map.of("text", "hi")), ctx);
        assertTrue(result.content().startsWith("Permission denied by user"));
    }

    @Test
    void allow_decision_executes_tool_without_asking_user() {
        var checker = new PermissionChecker(PermissionMode.AUTO)
                .addAllowRule(new PermissionRule("echo", PermissionBehavior.ALLOW, null));
        // userConfirmation is never called — we use alwaysDeny to prove it's not called
        var ctx = ToolUseContext.defaults(".").withPermissions(checker, UserConfirmation.alwaysDeny());
        var router = new ToolRouter(registryWithEcho());
        var result = router.route(new ContentBlock.ToolUse("5", "echo", Map.of("text", "world")), ctx);
        assertEquals("ok:world", result.content());
    }

    @Test
    void plan_mode_denies_bash_even_if_registered() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("bash", "runs bash", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("executed");
            }
        });
        var checker = new PermissionChecker(PermissionMode.PLAN);
        var ctx = ToolUseContext.defaults(".").withPermissions(checker, UserConfirmation.alwaysDeny());
        var router = new ToolRouter(registry);
        var result = router.route(new ContentBlock.ToolUse("6", "bash", Map.of("command", "echo hi")), ctx);
        assertTrue(result.content().startsWith("Permission denied"));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn test -pl . -Dtest=ToolRouterPermissionTest -q 2>&1 | tail -5
```
Expected: FAIL — `withPermissions` does not exist on `ToolUseContext`

- [ ] **Step 3: Add permission fields to ToolUseContext**

Replace the entire content of `src/main/java/org/example/agent/tool/ToolUseContext.java`:

```java
package org.example.agent.tool;

import org.example.agent.permission.PermissionChecker;
import org.example.agent.permission.UserConfirmation;
import org.example.agent.tool.todo.PlanningState;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ToolUseContext {

    private final Map<String, Object> permissionContext;
    private final Map<String, Object> mcpClients;
    private final Map<String, Object> appState;
    private final List<String> notifications;
    private final String cwd;
    private final PlanningState planningState;
    private final PermissionChecker permissionChecker;
    private final UserConfirmation userConfirmation;

    private ToolUseContext(Map<String, Object> permissionContext,
                           Map<String, Object> mcpClients,
                           Map<String, Object> appState,
                           List<String> notifications,
                           String cwd,
                           PlanningState planningState,
                           PermissionChecker permissionChecker,
                           UserConfirmation userConfirmation) {
        this.permissionContext = permissionContext;
        this.mcpClients = mcpClients;
        this.appState = appState;
        this.notifications = notifications;
        this.cwd = cwd;
        this.planningState = planningState;
        this.permissionChecker = permissionChecker;
        this.userConfirmation = userConfirmation;
    }

    public static ToolUseContext defaults(String cwd) {
        Objects.requireNonNull(cwd, "cwd must not be null");
        return new ToolUseContext(Map.of(), Map.of(), Map.of(), List.of(), cwd,
                new PlanningState(), null, null);
    }

    public Map<String, Object> permissionContext() { return permissionContext; }
    public Map<String, Object> mcpClients()        { return mcpClients; }
    public Map<String, Object> appState()          { return appState; }
    public List<String>        notifications()     { return notifications; }
    public String              cwd()               { return cwd; }
    public PlanningState       planningState()     { return planningState; }
    public PermissionChecker   permissionChecker() { return permissionChecker; }
    public UserConfirmation    userConfirmation()  { return userConfirmation; }

    public ToolUseContext withNotifications(List<String> notifications) {
        Objects.requireNonNull(notifications, "notifications must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                List.copyOf(notifications), cwd, planningState, permissionChecker, userConfirmation);
    }

    public ToolUseContext withPermissions(PermissionChecker checker, UserConfirmation confirmation) {
        Objects.requireNonNull(checker, "checker must not be null");
        Objects.requireNonNull(confirmation, "confirmation must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, checker, confirmation);
    }
}
```

- [ ] **Step 4: Run tests to verify ToolUseContext compiles and existing tests pass**

```
mvn test -pl . -Dtest=ToolRouterPermissionTest,ToolRouterTest -q 2>&1 | tail -8
```
Expected: `ToolRouterTest` passes; `ToolRouterPermissionTest` fails at `withPermissions` (now compiles) — but `ToolRouter` doesn't check permissions yet

- [ ] **Step 5: Commit ToolUseContext changes**

```bash
git add src/main/java/org/example/agent/tool/ToolUseContext.java
git commit -m "feat(permission): add permissionChecker and userConfirmation to ToolUseContext"
```

---

### Task 7: Wire permission into ToolRouter

**Files:**
- Modify: `src/main/java/org/example/agent/tool/ToolRouter.java`

- [ ] **Step 1: Modify ToolRouter.routeToEnvelope() to enforce permission**

Replace the entire content of `src/main/java/org/example/agent/tool/ToolRouter.java`:

```java
package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.permission.PermissionBehavior;

import java.util.Objects;

public class ToolRouter {

    private final ToolRegistry registry;

    public ToolRouter(ToolRegistry registry) {
        this.registry = registry;
    }

    public ContentBlock.ToolResult route(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        var envelope = routeToEnvelope(toolUse, ctx);
        return new ContentBlock.ToolResult(toolUse.id(), envelope.content());
    }

    public ToolResultEnvelope routeToEnvelope(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        if (toolUse.name().startsWith("mcp__")) {
            throw new UnsupportedOperationException("MCP tools not implemented (s19)");
        }

        var checker = ctx.permissionChecker();
        if (checker != null) {
            var decision = checker.check(toolUse.name(), toolUse.input());
            if (decision.behavior() == PermissionBehavior.DENY) {
                return ToolResultEnvelope.error("Permission denied: " + decision.reason());
            }
            if (decision.behavior() == PermissionBehavior.ASK) {
                boolean approved = ctx.userConfirmation().confirm(
                        toolUse.name(), toolUse.input(), decision.reason());
                if (!approved) {
                    return ToolResultEnvelope.error("Permission denied by user");
                }
            }
            // ALLOW falls through to execution
        }

        var tool = registry.get(toolUse.name());
        if (tool == null) throw new UnknownToolException(toolUse.name());
        return tool.execute(toolUse.input(), ctx);
    }

    public boolean isConcurrencySafe(String toolName) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (toolName.startsWith("mcp__")) return false;
        var tool = registry.get(toolName);
        return tool != null && tool.isConcurrencySafe();
    }
}
```

- [ ] **Step 2: Run all ToolRouter tests**

```
mvn test -pl . -Dtest=ToolRouterPermissionTest,ToolRouterTest -q
```
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 3: Run full test suite to check for regressions**

```
mvn test -pl . -q
```
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/example/agent/tool/ToolRouter.java \
        src/test/java/org/example/agent/tool/ToolRouterPermissionTest.java
git commit -m "feat(permission): enforce permission pipeline in ToolRouter before tool execution"
```

---

### Task 8: Wire permission into QueryEngine

**Files:**
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`
- Test: inline in `PermissionCheckerTest.java` → actually a new integration test class is cleaner

> **Note:** This task wires permission into `QueryEngine` so callers can pass a `PermissionChecker` without constructing `ToolUseContext` manually. The `run()` method currently hardcodes `ToolUseContext.defaults(cwd)` — we add a new constructor that carries a checker+confirmation pair and applies it when building the context.

**Files:**
- Create: `src/test/java/org/example/agent/engine/QueryEnginePermissionTest.java`
- Modify: `src/main/java/org/example/agent/engine/QueryEngine.java`

- [ ] **Step 1: Write the failing integration test**

Create `src/test/java/org/example/agent/engine/QueryEnginePermissionTest.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.*;
import org.example.agent.permission.*;
import org.example.agent.tool.*;
import org.example.agent.core.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryEnginePermissionTest {

    // Fake model: returns one tool_use then END_TURN
    private static ModelClient fakeModel(String toolName, Map<String, Object> toolInput) {
        var[] callCount = {0};
        return request -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return new ModelResponse(
                        List.of(new ContentBlock.ToolUse("tid-1", toolName, toolInput)),
                        StopReason.TOOL_USE);
            }
            return new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN);
        };
    }

    private static ToolRegistry registryWithDummyBash() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("bash", "runs bash", Map.of(
                        "type", "object",
                        "properties", Map.of("command", Map.of("type", "string")),
                        "required", List.of("command")));
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("bash ran: " + input.get("command"));
            }
        });
        return registry;
    }

    @Test
    void plan_mode_causes_tool_result_to_contain_permission_denied() {
        var checker = new PermissionChecker(PermissionMode.PLAN);
        var registry = registryWithDummyBash();
        var engine = new QueryEngine(
                fakeModel("bash", Map.of("command", "echo hello")),
                registry,
                checker,
                UserConfirmation.alwaysDeny()
        );
        var params = new QueryParams(
                List.of(Message.user("run bash")),
                null, null, null, 5
        );
        var result = (QueryResult.Success) engine.run(params);
        // the tool result message must contain "Permission denied"
        var toolResultContent = result.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.ToolResult)
                .map(b -> ((ContentBlock.ToolResult) b).content())
                .findFirst()
                .orElse("");
        assertTrue(toolResultContent.contains("Permission denied"),
                "Expected Permission denied in tool result but got: " + toolResultContent);
    }

    @Test
    void auto_mode_allows_read_file_without_user_prompt() {
        var checker = new PermissionChecker(PermissionMode.AUTO);
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("read_file", "reads file", Map.of(
                        "type", "object",
                        "properties", Map.of("path", Map.of("type", "string")),
                        "required", List.of("path")));
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("file content");
            }
        });
        // alwaysDeny proves user was never asked — if it was asked, the tool would fail
        var engine = new QueryEngine(
                fakeModel("read_file", Map.of("path", "README.md")),
                registry,
                checker,
                UserConfirmation.alwaysDeny()
        );
        var params = new QueryParams(
                List.of(Message.user("read readme")),
                null, null, null, 5
        );
        var result = (QueryResult.Success) engine.run(params);
        var toolResultContent = result.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.ToolResult)
                .map(b -> ((ContentBlock.ToolResult) b).content())
                .findFirst()
                .orElse("");
        assertEquals("file content", toolResultContent);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
mvn test -pl . -Dtest=QueryEnginePermissionTest -q 2>&1 | tail -5
```
Expected: FAIL — no `QueryEngine` constructor matches `(ModelClient, ToolRegistry, PermissionChecker, UserConfirmation)`

- [ ] **Step 3: Add permission-aware constructor to QueryEngine**

In `src/main/java/org/example/agent/engine/QueryEngine.java`, add:
1. Two new instance fields at the top (after existing fields):

```java
    private final PermissionChecker permissionChecker;   // nullable
    private final UserConfirmation   userConfirmation;   // nullable
```

2. New public constructor (after the existing ones):

```java
    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, UserConfirmation userConfirmation) {
        this(modelClient, toolRegistry, null, defaultCompactor(),
             ForkJoinPool.commonPool(), permissionChecker, userConfirmation);
    }
```

3. Update the private canonical constructor (the one all others delegate to) to add the two new params at the end:

```java
    private QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                        SkillRegistry skillRegistry, ContextCompactor compactor,
                        ExecutorService executor,
                        PermissionChecker permissionChecker, UserConfirmation userConfirmation) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.compactor = compactor;
        this.permissionChecker = permissionChecker;
        this.userConfirmation = userConfirmation;
        var router = new ToolRouter(toolRegistry);
        this.runtime = new ToolExecutionRuntime(router, executor);
        toolRegistry.register(new CompactTool(
                compactor,
                () -> List.copyOf(currentState.messages()),
                msgs -> currentState.replaceMessages(msgs),
                () -> currentCtx.planningState()
        ));
    }
```

4. Update all existing constructors that delegate to the private one — they must now pass `null, null` as the last two args:

```java
    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this(modelClient, toolRegistry, null, defaultCompactor(), ForkJoinPool.commonPool(), null, null);
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, ExecutorService executor) {
        this(modelClient, toolRegistry, null, defaultCompactor(), executor, null, null);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                       SkillRegistry skillRegistry) {
        this(modelClient, toolRegistry, skillRegistry, defaultCompactor(), ForkJoinPool.commonPool(), null, null);
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                ContextCompactor compactor, ExecutorService executor) {
        this(modelClient, toolRegistry, null, compactor, executor, null, null);
    }
```

5. Update `run()` to apply the permission checker when building `currentCtx`:

```java
    public QueryResult run(QueryParams params) {
        currentState = QueryState.from(params);
        var baseCtx = ToolUseContext.defaults(System.getProperty("user.dir"));
        currentCtx = (permissionChecker != null)
                ? baseCtx.withPermissions(permissionChecker, userConfirmation)
                : baseCtx;
        // ... rest of run() unchanged
```

The complete updated `QueryEngine.java`:

```java
package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelClient;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.permission.PermissionChecker;
import org.example.agent.permission.UserConfirmation;
import org.example.agent.tool.*;
import org.example.agent.tool.skill.SkillRegistry;

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
    private final PermissionChecker permissionChecker;
    private final UserConfirmation userConfirmation;

    private QueryState     currentState;
    private ToolUseContext currentCtx;

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this(modelClient, toolRegistry, null, defaultCompactor(), ForkJoinPool.commonPool(), null, null);
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, ExecutorService executor) {
        this(modelClient, toolRegistry, null, defaultCompactor(), executor, null, null);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                       SkillRegistry skillRegistry) {
        this(modelClient, toolRegistry, skillRegistry, defaultCompactor(), ForkJoinPool.commonPool(), null, null);
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                ContextCompactor compactor, ExecutorService executor) {
        this(modelClient, toolRegistry, null, compactor, executor, null, null);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, UserConfirmation userConfirmation) {
        this(modelClient, toolRegistry, null, defaultCompactor(),
                ForkJoinPool.commonPool(), permissionChecker, userConfirmation);
    }

    private QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                        SkillRegistry skillRegistry, ContextCompactor compactor,
                        ExecutorService executor,
                        PermissionChecker permissionChecker, UserConfirmation userConfirmation) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.compactor = compactor;
        this.permissionChecker = permissionChecker;
        this.userConfirmation = userConfirmation;
        var router = new ToolRouter(toolRegistry);
        this.runtime = new ToolExecutionRuntime(router, executor);
        toolRegistry.register(new CompactTool(
                compactor,
                () -> List.copyOf(currentState.messages()),
                msgs -> currentState.replaceMessages(msgs),
                () -> currentCtx.planningState()
        ));
    }

    private static ContextCompactor defaultCompactor() {
        var dir = Paths.get(System.getProperty("user.dir"),
                ".task_outputs", "tool-results");
        return new ContextCompactor(dir);
    }

    public QueryResult run(QueryParams params) {
        currentState = QueryState.from(params);
        var baseCtx = ToolUseContext.defaults(System.getProperty("user.dir"));
        currentCtx = (permissionChecker != null)
                ? baseCtx.withPermissions(permissionChecker, userConfirmation)
                : baseCtx;

        while (true) {
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
        for (var r : results) {
            var content = compactor.persistIfLarge(r.toolUseId(), r.content());
            blocks.add(new ContentBlock.ToolResult(r.toolUseId(), content));
        }
        return new Message(Role.USER, List.copyOf(blocks));
    }
}
```

- [ ] **Step 4: Run QueryEngine permission integration tests**

```
mvn test -pl . -Dtest=QueryEnginePermissionTest -q
```
Expected: BUILD SUCCESS, 2 tests pass

- [ ] **Step 5: Run full test suite to check for regressions**

```
mvn test -pl . -q
```
Expected: BUILD SUCCESS, all existing tests pass + new tests

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/example/agent/engine/QueryEngine.java \
        src/test/java/org/example/agent/engine/QueryEnginePermissionTest.java
git commit -m "feat(permission): wire PermissionChecker into QueryEngine via new constructor"
```

---

## Self-Review

**Spec coverage check:**

| s07 Requirement | Task |
|---|---|
| 4-step pipeline: deny → mode → allow → ask | Task 3 + 4 |
| 3 modes: DEFAULT / PLAN / AUTO | Task 1 + 3 |
| PermissionRule data structure | Task 2 |
| PermissionDecision result | Task 2 |
| Bash special safety check (sudo, rm -rf, $(), backtick, pipe to sh/bash) | Task 4 |
| deny count tracking | Task 5 |
| Integration with tool execution pipeline | Task 6 + 7 |
| Backwards compatibility (no checker = no change) | Task 6 (no_permission_checker test) |

**Placeholder scan:** No TBDs, no "fill in later", all code blocks are complete.

**Type consistency:**
- `PermissionChecker.check()` → `PermissionDecision` throughout
- `PermissionBehavior.DENY/ALLOW/ASK` used consistently across all tasks
- `ToolUseContext.withPermissions(PermissionChecker, UserConfirmation)` referenced in Tasks 6, 7, 8
- `UserConfirmation.alwaysAllow()` / `alwaysDeny()` static factory methods defined in Task 2 and used in Tasks 6 and 8
