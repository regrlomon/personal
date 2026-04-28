package org.example.agent.tool.task;

import org.example.agent.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskToolsIntegrationTest {

    @TempDir Path tempDir;
    private ToolUseContext ctx;
    private TaskManager manager;

    @BeforeEach
    void setUp() {
        manager = new TaskManager(tempDir);
        ctx = ToolUseContext.defaults(tempDir.toString()).withTaskManager(manager);
    }

    // ── TaskCreateTool ──────────────────────────────────────────────────────

    @Test
    void create_returns_success_with_id_and_subject() {
        var tool = new TaskCreateTool();
        var result = tool.execute(Map.of("subject", "Write parser"), ctx);
        assertTrue(result.ok());
        assertEquals("Created task #1: Write parser", result.content());
    }

    @Test
    void create_returns_error_when_subject_blank() {
        var tool = new TaskCreateTool();
        var result = tool.execute(Map.of("subject", "  "), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void create_returns_error_when_subject_missing() {
        var tool = new TaskCreateTool();
        var result = tool.execute(Map.of(), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void create_returns_error_for_invalid_blockedBy_id() {
        var tool = new TaskCreateTool();
        var result = tool.execute(Map.of("subject", "X", "blockedBy", List.of(99)), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
        assertTrue(result.content().contains("99"));
    }

    @Test
    void create_definition_has_correct_name() {
        assertEquals("task_create", new TaskCreateTool().definition().name());
    }

    // ── TaskUpdateTool ──────────────────────────────────────────────────────

    @Test
    void update_returns_updated_message() {
        manager.create("Task A", "", List.of());
        var tool = new TaskUpdateTool();
        var result = tool.execute(Map.of("id", 1, "status", "in_progress"), ctx);
        assertTrue(result.ok());
        assertEquals("Updated task #1", result.content());
    }

    @Test
    void update_complete_returns_unblocked_list() {
        manager.create("Task A", "", List.of());
        manager.create("Task B", "", List.of(1));
        var tool = new TaskUpdateTool();
        var result = tool.execute(Map.of("id", 1, "status", "completed"), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("unblocked"));
        assertTrue(result.content().contains("2"));
    }

    @Test
    void update_returns_error_for_missing_id() {
        var tool = new TaskUpdateTool();
        var result = tool.execute(Map.of("id", 99, "status", "completed"), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void update_returns_error_for_invalid_status() {
        manager.create("Task A", "", List.of());
        var tool = new TaskUpdateTool();
        var result = tool.execute(Map.of("id", 1, "status", "bogus"), ctx);
        assertFalse(result.ok());
        assertTrue(result.content().contains("bogus"));
    }

    @Test
    void update_definition_has_correct_name() {
        assertEquals("task_update", new TaskUpdateTool().definition().name());
    }

    // ── TaskGetTool ─────────────────────────────────────────────────────────

    @Test
    void get_returns_task_details() {
        manager.create("Write parser", "some desc", List.of());
        var tool = new TaskGetTool();
        var result = tool.execute(Map.of("id", 1), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("#1"));
        assertTrue(result.content().contains("Write parser"));
        assertTrue(result.content().contains("some desc"));
    }

    @Test
    void get_returns_error_for_missing_id() {
        var tool = new TaskGetTool();
        var result = tool.execute(Map.of("id", 99), ctx);
        assertFalse(result.ok());
        assertTrue(result.content().contains("99"));
    }

    @Test
    void get_definition_has_correct_name() {
        assertEquals("task_get", new TaskGetTool().definition().name());
    }

    // ── TaskListTool ────────────────────────────────────────────────────────

    @Test
    void list_shows_all_tasks_with_status_markers() {
        manager.create("Write parser", "", List.of());
        manager.create("Write tests", "", List.of(1));
        var tool = new TaskListTool();
        var result = tool.execute(Map.of(), ctx);
        assertTrue(result.ok());
        String out = result.content();
        assertTrue(out.contains("#1"));
        assertTrue(out.contains("#2"));
        assertTrue(out.contains("pending*"));
    }

    @Test
    void list_returns_no_tasks_message_when_empty() {
        var result = new TaskListTool().execute(Map.of(), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("no tasks"));
    }

    @Test
    void list_definition_has_correct_name() {
        assertEquals("task_list", new TaskListTool().definition().name());
    }
}
