package org.example.agent.tool.background;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

public class BackgroundCheckTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "background_check",
            "Check the status of a background task by its id.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "id", Map.of("type", "string", "description", "Task id returned by background_run")
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

        long elapsedSec = (System.currentTimeMillis() - record.startedAt()) / 1000;

        if (record.status() == RuntimeTaskStatus.RUNNING) {
            return ToolResultEnvelope.success(
                    "[" + id + "] RUNNING - started " + elapsedSec + "s ago: " + record.description());
        }

        return ToolResultEnvelope.success(
                "[" + id + "] " + record.status() + " - " + record.description() + "\n" +
                "Preview: " + record.resultPreview() + "\n" +
                "Full output: .runtime-tasks/" + id + ".log");
    }
}
