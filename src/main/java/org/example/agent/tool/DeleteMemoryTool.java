package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;
import org.example.agent.engine.MemoryStore;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class DeleteMemoryTool implements Tool {

    private final MemoryStore store;

    public DeleteMemoryTool(MemoryStore store) {
        this.store = store;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "delete_memory",
                "Delete a memory entry by name.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "name", Map.of("type", "string", "description", "Name of the memory entry to delete")
                        ),
                        "required", List.of("name")
                )
        );
    }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var name = (String) input.get("name");
        try {
            boolean deleted = store.delete(name);
            return ToolResultEnvelope.success(deleted ? "Memory deleted: " + name : "Memory not found: " + name);
        } catch (IOException e) {
            return ToolResultEnvelope.error("Error deleting memory: " + e.getMessage());
        }
    }
}
