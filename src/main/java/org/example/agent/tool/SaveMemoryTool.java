package org.example.agent.tool;

import org.example.agent.core.MemoryEntry;
import org.example.agent.core.ToolDefinition;
import org.example.agent.engine.MemoryStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class SaveMemoryTool implements Tool {

    private final MemoryStore store;

    public SaveMemoryTool(MemoryStore store) {
        this.store = store;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "save_memory",
                "Save or update a memory entry. An existing entry with the same name is overwritten.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "Unique identifier for this memory"),
                                "description", Map.of("type", "string", "description", "One-line summary for the memory index"),
                                "type", Map.of("type", "string", "description", "Category: user | feedback | project | reference"),
                                "content", Map.of("type", "string", "description", "Memory body text")
                        ),
                        "required", List.of("name", "description", "type", "content")
                )
        );
    }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var entry = new MemoryEntry(
                (String) input.get("name"),
                (String) input.get("description"),
                (String) input.get("type"),
                (String) input.get("content")
        );
        try {
            store.save(entry);
            return ToolResultEnvelope.success("Memory saved: " + entry.name());
        } catch (IOException e) {
            return ToolResultEnvelope.error("Error saving memory: " + e.getMessage());
        }
    }
}
