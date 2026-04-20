package org.example.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {

    @TempDir
    Path workdir;

    PathSandbox sandbox;
    ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        sandbox = new PathSandbox(workdir);
        ctx = ToolUseContext.defaults(workdir.toString());
    }

    // --- ReadFileTool ---

    @Test
    void read_returns_file_contents() throws IOException {
        Files.writeString(workdir.resolve("hello.txt"), "line1\nline2\nline3");
        var tool = new ReadFileTool(sandbox);
        var result = tool.execute(Map.of("path", "hello.txt"), ctx);
        assertEquals("line1\nline2\nline3", result.content());
    }

    @Test
    void read_truncates_to_limit() throws IOException {
        Files.writeString(workdir.resolve("big.txt"), "a\nb\nc\nd\ne");
        var tool = new ReadFileTool(sandbox);
        var result = tool.execute(Map.of("path", "big.txt", "limit", 3), ctx);
        assertEquals("a\nb\nc", result.content());
    }

    @Test
    void read_returns_error_on_missing_file() {
        var tool = new ReadFileTool(sandbox);
        var result = tool.execute(Map.of("path", "missing.txt"), ctx);
        assertTrue(result.content().startsWith("Error:"));
    }

    @Test
    void read_rejects_path_outside_workdir() {
        var tool = new ReadFileTool(sandbox);
        var result = tool.execute(Map.of("path", "../../etc/passwd"), ctx);
        assertTrue(result.content().startsWith("Error:"));
    }

    // --- WriteFileTool ---

    @Test
    void write_creates_file_with_content() {
        var tool = new WriteFileTool(sandbox);
        tool.execute(Map.of("path", "out.txt", "content", "hello"), ctx);
        assertDoesNotThrow(() -> {
            var written = Files.readString(workdir.resolve("out.txt"));
            assertEquals("hello", written);
        });
    }

    @Test
    void write_overwrites_existing_file() throws IOException {
        Files.writeString(workdir.resolve("existing.txt"), "old");
        var tool = new WriteFileTool(sandbox);
        tool.execute(Map.of("path", "existing.txt", "content", "new"), ctx);
        assertEquals("new", Files.readString(workdir.resolve("existing.txt")));
    }

    @Test
    void write_rejects_path_outside_workdir() {
        var tool = new WriteFileTool(sandbox);
        var result = tool.execute(Map.of("path", "../../evil.txt", "content", "x"), ctx);
        assertTrue(result.content().startsWith("Error:"));
    }

    // --- EditFileTool ---

    @Test
    void edit_replaces_old_text_with_new_text() throws IOException {
        Files.writeString(workdir.resolve("code.java"), "int x = 1;");
        var tool = new EditFileTool(sandbox);
        tool.execute(Map.of("path", "code.java", "old_text", "int x = 1;", "new_text", "int x = 42;"), ctx);
        assertEquals("int x = 42;", Files.readString(workdir.resolve("code.java")));
    }

    @Test
    void edit_returns_error_when_old_text_not_found() throws IOException {
        Files.writeString(workdir.resolve("code.java"), "int x = 1;");
        var tool = new EditFileTool(sandbox);
        var result = tool.execute(Map.of("path", "code.java", "old_text", "NOT FOUND", "new_text", "x"), ctx);
        assertTrue(result.content().startsWith("Error:"));
    }

    @Test
    void edit_rejects_path_outside_workdir() {
        var tool = new EditFileTool(sandbox);
        var result = tool.execute(Map.of("path", "../../evil.java", "old_text", "a", "new_text", "b"), ctx);
        assertTrue(result.content().startsWith("Error:"));
    }
}
