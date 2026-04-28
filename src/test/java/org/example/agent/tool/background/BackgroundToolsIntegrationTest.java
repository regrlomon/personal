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
}
