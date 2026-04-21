package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import java.util.List;
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

    @Test
    void withNotifications_returns_new_context_with_updated_list() {
        var ctx = ToolUseContext.defaults(".");
        var updated = ctx.withNotifications(List.of("msg1", "msg2"));
        assertEquals(List.of("msg1", "msg2"), updated.notifications());
        assertEquals(".", updated.cwd()); // other fields unchanged
    }

    @Test
    void withNotifications_original_context_is_unchanged() {
        var ctx = ToolUseContext.defaults(".");
        ctx.withNotifications(List.of("msg1"));
        assertTrue(ctx.notifications().isEmpty()); // original unaffected
    }

    @Test
    void withNotifications_makes_defensive_copy() {
        var ctx = ToolUseContext.defaults(".");
        var mutable = new java.util.ArrayList<String>();
        mutable.add("before");
        var updated = ctx.withNotifications(mutable);
        mutable.add("after"); // mutate original list
        assertEquals(List.of("before"), updated.notifications()); // copy not affected
    }
}

