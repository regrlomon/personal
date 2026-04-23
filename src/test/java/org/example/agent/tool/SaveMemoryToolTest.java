package org.example.agent.tool;

import org.example.agent.engine.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SaveMemoryToolTest {

    @TempDir
    Path dir;

    MemoryStore store;
    SaveMemoryTool tool;
    ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(dir);
        tool = new SaveMemoryTool(store);
        ctx = ToolUseContext.defaults(dir.toString());
    }

    @Test
    void execute_saves_memory_and_returns_success_message() throws IOException {
        var result = tool.execute(Map.of(
                "name", "prefer_tabs",
                "description", "Use tabs not spaces",
                "type", "user",
                "content", "Always indent with tabs."
        ), ctx);

        assertFalse(result.isError());
        assertEquals("Memory saved: prefer_tabs", result.content());
        assertEquals(1, store.loadAll().size());
        assertEquals("prefer_tabs", store.loadAll().get(0).name());
    }

    @Test
    void execute_overwrites_existing_memory_with_same_name() throws IOException {
        tool.execute(Map.of("name", "n", "description", "d", "type", "user", "content", "v1"), ctx);
        tool.execute(Map.of("name", "n", "description", "d2", "type", "user", "content", "v2"), ctx);

        var entries = store.loadAll();
        assertEquals(1, entries.size());
        assertEquals("v2", entries.get(0).content());
    }

    @Test
    void tool_name_is_save_memory() {
        assertEquals("save_memory", tool.definition().name());
    }

    @Test
    void definition_requires_all_four_fields() {
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) tool.definition().inputSchema().get("required");
        assertTrue(required.contains("name"));
        assertTrue(required.contains("description"));
        assertTrue(required.contains("type"));
        assertTrue(required.contains("content"));
    }
}
