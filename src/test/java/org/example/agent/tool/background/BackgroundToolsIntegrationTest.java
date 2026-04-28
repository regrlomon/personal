package org.example.agent.tool.background;

import org.example.agent.tool.ToolUseContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundToolsIntegrationTest {

    @TempDir Path tempDir;
    private BackgroundManager manager;
    private ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        manager = new BackgroundManager(tempDir);
        ctx = ToolUseContext.defaults(tempDir.toString()).withBackgroundManager(manager);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    // ── BackgroundRunTool ────────────────────────────────────────────────────

    @Test
    void run_returns_started_message_with_id() {
        var tool = new BackgroundRunTool();
        var result = tool.execute(Map.of("command", "echo hello"), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().startsWith("Started background task ["));
        assertTrue(result.content().contains("echo hello"));
    }

    @Test
    void run_uses_default_timeout_300() {
        var tool = new BackgroundRunTool();
        var result = tool.execute(Map.of("command", "echo x"), ctx);
        assertTrue(result.ok());
    }

    @Test
    void run_returns_error_when_command_missing() {
        var tool = new BackgroundRunTool();
        var result = tool.execute(Map.of(), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void run_returns_error_when_manager_not_configured() {
        var ctxNoManager = ToolUseContext.defaults(tempDir.toString());
        var result = new BackgroundRunTool().execute(Map.of("command", "echo x"), ctxNoManager);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void run_definition_has_correct_name() {
        assertEquals("background_run", new BackgroundRunTool().definition().name());
    }

    // ── BackgroundCheckTool ──────────────────────────────────────────────────

    @Test
    void check_running_task_shows_running_status() throws InterruptedException {
        var id = manager.submit(new ShellBackgroundTask("sleep 10", 30));
        var tool = new BackgroundCheckTool();
        var result = tool.execute(Map.of("id", id), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("RUNNING"));
        assertTrue(result.content().contains(id));
    }

    @Test
    void check_completed_task_shows_preview_and_log_path() throws InterruptedException {
        var id = manager.submit(new ShellBackgroundTask("echo finished", 10));
        waitForStatus(id, RuntimeTaskStatus.COMPLETED, 3000);
        var tool = new BackgroundCheckTool();
        var result = tool.execute(Map.of("id", id), ctx);
        assertTrue(result.ok());
        assertTrue(result.content().contains("COMPLETED"));
        assertTrue(result.content().contains("finished"));
        assertTrue(result.content().contains(".log"));
    }

    @Test
    void check_returns_error_for_unknown_id() {
        var tool = new BackgroundCheckTool();
        var result = tool.execute(Map.of("id", "notexist"), ctx);
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void check_definition_has_correct_name() {
        assertEquals("background_check", new BackgroundCheckTool().definition().name());
    }

    private void waitForStatus(String id, RuntimeTaskStatus expected, long timeoutMs)
            throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var record = manager.check(id);
            if (record != null && record.status() == expected) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for " + expected);
    }
}
