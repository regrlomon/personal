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
