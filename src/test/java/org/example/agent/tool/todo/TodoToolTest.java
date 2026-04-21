package org.example.agent.tool.todo;

import org.example.agent.tool.ToolUseContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TodoToolTest {

    private final TodoTool tool = new TodoTool();
    private final ToolUseContext ctx = ToolUseContext.defaults(".");

    @Test
    void is_not_concurrency_safe() {
        assertFalse(tool.isConcurrencySafe());
    }

    @Test
    void execute_updates_planning_state_and_returns_rendered_plan() {
        var items = List.of(
            Map.<String, Object>of("content", "step 1", "status", "in_progress", "activeForm", "doing step 1"),
            Map.<String, Object>of("content", "step 2", "status", "pending", "activeForm", "")
        );
        var result = tool.execute(Map.of("items", items), ctx);

        assertTrue(result.ok());
        assertEquals(2, ctx.planningState().items().size());
        assertTrue(result.content().contains("[>] step 1"));
        assertTrue(result.content().contains("[ ] step 2"));
    }

    @Test
    void execute_rejects_multiple_in_progress_with_error_envelope() {
        var items = List.of(
            Map.<String, Object>of("content", "a", "status", "in_progress", "activeForm", ""),
            Map.<String, Object>of("content", "b", "status", "in_progress", "activeForm", "")
        );
        var result = tool.execute(Map.of("items", items), ctx);

        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void execute_resets_rounds_since_update() {
        ctx.planningState().tickRound();
        ctx.planningState().tickRound();
        tool.execute(Map.of("items", List.of()), ctx);
        assertEquals(0, ctx.planningState().roundsSinceUpdate());
    }

    @Test
    void definition_has_correct_name() {
        assertEquals("todo", tool.definition().name());
    }

    @Test
    void execute_returns_error_when_items_field_missing() {
        var result = tool.execute(Map.of(), ctx);  // no "items" key
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void execute_returns_error_for_unknown_status() {
        var items = List.of(
            Map.<String, Object>of("content", "task", "status", "unknown_status", "activeForm", "")
        );
        var result = tool.execute(Map.of("items", items), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }
}
