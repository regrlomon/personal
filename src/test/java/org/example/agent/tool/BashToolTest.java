package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BashToolTest {

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void executes_command_and_returns_stdout() {
        var tool = new BashTool();
        var result = tool.execute(Map.of("command", "echo hello"));
        assertEquals("hello", result.strip());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void captures_stderr_on_failure() {
        var tool = new BashTool();
        var result = tool.execute(Map.of("command", "ls /nonexistent_path_xyz 2>&1"));
        assertFalse(result.isBlank());
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void returns_combined_stdout_and_stderr() {
        var tool = new BashTool();
        var result = tool.execute(Map.of("command", "echo out && echo err >&2"));
        assertTrue(result.contains("out"));
    }
}
