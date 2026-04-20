package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolUseContextTest {

    @Test
    void defaults_sets_cwd_and_empty_stub_collections() {
        var ctx = ToolUseContext.defaults("/workspace");
        assertEquals("/workspace", ctx.cwd());
        assertTrue(ctx.permissionContext().isEmpty());
        assertTrue(ctx.mcpClients().isEmpty());
        assertTrue(ctx.appState().isEmpty());
        assertTrue(ctx.notifications().isEmpty());
    }
}
