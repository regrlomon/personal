package org.example.agent.tool.background;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundManagerTest {

    @TempDir Path tempDir;

    @Test
    void shell_task_writes_output_to_file() throws Exception {
        var outFile = tempDir.resolve("out.log");
        var task = new ShellBackgroundTask("echo hello_world", 10);
        task.execute(outFile);
        var content = Files.readString(outFile);
        assertTrue(content.contains("hello_world"));
    }

    @Test
    void shell_task_preview_returns_first_500_chars() throws Exception {
        var outFile = tempDir.resolve("out.log");
        Files.writeString(outFile, "a".repeat(600));
        var task = new ShellBackgroundTask("echo x", 10);
        var preview = task.preview(outFile);
        assertEquals(503, preview.length()); // 500 + "..."
        assertTrue(preview.endsWith("..."));
    }

    @Test
    void callable_task_writes_return_value_to_file() throws Exception {
        var outFile = tempDir.resolve("out.log");
        var task = new CallableBackgroundTask("compute", () -> "result_42");
        task.execute(outFile);
        assertEquals("result_42", Files.readString(outFile).trim());
    }
}
