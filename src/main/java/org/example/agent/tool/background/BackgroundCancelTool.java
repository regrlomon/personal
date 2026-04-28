package org.example.agent.tool.background;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

public class BackgroundCancelTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "background_cancel",
            "Cancel a running background task.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "id", Map.of("type", "string", "description", "Task id to cancel")
                    ),
                    "required", List.of("id")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var id = (String) input.get("id");
        if (id == null || id.isBlank()) return ToolResultEnvelope.error("id must not be blank");

        var manager = ctx.backgroundManager();
        if (manager == null) return ToolResultEnvelope.error("background manager not configured");

        var record = manager.check(id);
        if (record == null) return ToolResultEnvelope.error("No background task with id: " + id);

        if (record.status() != RuntimeTaskStatus.RUNNING) {
            return ToolResultEnvelope.success(
                    "[" + id + "] not cancellable (already " + record.status() + ")");
        }

        manager.cancel(id);
        return ToolResultEnvelope.success("[" + id + "] cancelled");
    }
}
