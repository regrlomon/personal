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
}
