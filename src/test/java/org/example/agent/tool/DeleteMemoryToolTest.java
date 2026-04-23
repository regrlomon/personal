package org.example.agent.tool;

import org.example.agent.core.MemoryEntry;
import org.example.agent.engine.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeleteMemoryToolTest {

    @TempDir
    Path dir;

    MemoryStore store;
    DeleteMemoryTool tool;
    ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(dir);
        tool = new DeleteMemoryTool(store);
        ctx = ToolUseContext.defaults(dir.toString());
    }

    @Test
    void execute_deletes_existing_memory_and_returns_success() throws IOException {
        store.save(new MemoryEntry("to_go", "desc", "user", "body"));

        var result = tool.execute(Map.of("name", "to_go"), ctx);

        assertFalse(result.isError());
        assertEquals("Memory deleted: to_go", result.content());
        assertEquals(0, store.loadAll().size());
    }

    @Test
    void execute_returns_not_found_message_when_absent() {
        var result = tool.execute(Map.of("name", "ghost"), ctx);

        assertFalse(result.isError());
        assertEquals("Memory not found: ghost", result.content());
    }

    @Test
    void tool_name_is_delete_memory() {
        assertEquals("delete_memory", tool.definition().name());
    }

    @Test
    void definition_requires_name_field() {
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) tool.definition().inputSchema().get("required");
        assertTrue(required.contains("name"));
    }
}
