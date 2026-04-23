package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineMemoryTest {

    @TempDir
    Path workdir;

    @Test
    void memory_section_appears_in_system_prompt() throws IOException {
        var memoryDir = workdir.resolve(".memory");
        var store = new MemoryStore(memoryDir);
        store.save(new MemoryEntry("pref_tabs", "Use tabs not spaces", "user", "Always indent with tabs."));

        var capturedPrompt = new String[1];
        var engine = new QueryEngine(
                request -> {
                    capturedPrompt[0] = request.systemPrompt();
                    return new ModelResponse(
                            List.of(new ContentBlock.Text("done")),
                            StopReason.END_TURN, 10, 5
                    );
                },
                new ToolRegistry(),
                store
        );

        engine.run(new QueryParams(
                List.of(Message.user("hello")),
                "base system prompt",
                null, null, null
        ));

        assertNotNull(capturedPrompt[0]);
        assertTrue(capturedPrompt[0].contains("## Memories"),
                "Expected ## Memories header, got: " + capturedPrompt[0]);
        assertTrue(capturedPrompt[0].contains("pref_tabs [user]"),
                "Expected memory name and type");
        assertTrue(capturedPrompt[0].contains("Always indent with tabs."),
                "Expected memory content");
        assertTrue(capturedPrompt[0].contains("base system prompt"),
                "Base prompt must still be present");
    }

    @Test
    void no_memory_section_when_store_is_null() {
        var capturedPrompt = new String[1];
        var engine = new QueryEngine(
                request -> {
                    capturedPrompt[0] = request.systemPrompt();
                    return new ModelResponse(
                            List.of(new ContentBlock.Text("done")),
                            StopReason.END_TURN, 10, 5
                    );
                },
                new ToolRegistry()
        );

        engine.run(new QueryParams(
                List.of(Message.user("hello")),
                "just base",
                null, null, null
        ));

        assertEquals("just base", capturedPrompt[0]);
    }

    @Test
    void no_memory_section_when_memory_dir_is_empty() throws IOException {
        var store = new MemoryStore(workdir.resolve(".memory"));

        var capturedPrompt = new String[1];
        var engine = new QueryEngine(
                request -> {
                    capturedPrompt[0] = request.systemPrompt();
                    return new ModelResponse(
                            List.of(new ContentBlock.Text("done")),
                            StopReason.END_TURN, 10, 5
                    );
                },
                new ToolRegistry(),
                store
        );

        engine.run(new QueryParams(
                List.of(Message.user("hello")),
                "base only",
                null, null, null
        ));

        assertEquals("base only", capturedPrompt[0]);
    }
}
