package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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

    @Test
    void defaults_creates_non_null_planning_state() {
        var ctx = ToolUseContext.defaults("/workspace");
        assertNotNull(ctx.planningState());
    }

    @Test
    void withNotifications_preserves_same_planning_state_reference() {
        var ctx = ToolUseContext.defaults(".");
        var updated = ctx.withNotifications(List.of("msg"));
        assertSame(ctx.planningState(), updated.planningState());
    }

    @Test
    void defaults_creates_null_task_manager() {
        var ctx = ToolUseContext.defaults("/workspace");
        assertNull(ctx.taskManager());
    }

    @Test
    void withTaskManager_returns_new_context_with_task_manager() {
        var ctx = ToolUseContext.defaults(".");
        var manager = new org.example.agent.tool.task.TaskManager(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                        "test-tasks-" + System.nanoTime()));
        var updated = ctx.withTaskManager(manager);
        assertSame(manager, updated.taskManager());
        assertSame(ctx.planningState(), updated.planningState());
    }

    @Test
    void withBackgroundManager_returns_new_context_with_manager(@TempDir java.nio.file.Path tempDir) {
        var ctx = ToolUseContext.defaults(tempDir.toString());
        var manager = new org.example.agent.tool.background.BackgroundManager(tempDir);
        var updated = ctx.withBackgroundManager(manager);
        assertSame(manager, updated.backgroundManager());
        assertNull(ctx.backgroundManager());
        manager.shutdown();
    }
}

