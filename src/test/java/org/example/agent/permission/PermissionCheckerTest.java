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
        assertEquals(PermissionBehavior.ASK, decision.behavior());
    }
}
