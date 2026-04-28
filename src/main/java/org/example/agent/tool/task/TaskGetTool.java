package org.example.agent.tool.task;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

public class TaskGetTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "task_get",
            "Get details of a single task by id.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "id", Map.of("type", "integer", "description", "Task ID")
                    ),
                    "required", List.of("id")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        Object rawId = input.get("id");
        if (!(rawId instanceof Number)) return ToolResultEnvelope.error("id must be an integer");
        int id = ((Number) rawId).intValue();

        var taskManager = ctx.taskManager();
        if (taskManager == null) return ToolResultEnvelope.error("task manager not configured");

        try {
            return ToolResultEnvelope.success(format(taskManager.get(id)));
        } catch (NoSuchTaskException e) {
            return ToolResultEnvelope.error(e.getMessage());
        } catch (UncheckedIOException e) {
            return ToolResultEnvelope.error("IO error: " + e.getCause().getMessage());
        }
    }

    // package-private for reuse
    static String format(TaskRecord t) {
        var sb = new StringBuilder();
        sb.append("#").append(t.id())
          .append(" [").append(t.status().name().toLowerCase()).append("] ")
          .append(t.subject()).append("\n");
        sb.append("blockedBy: ").append(t.blockedBy())
          .append("  blocks: ").append(t.blocks())
          .append("  owner: \"").append(t.owner()).append("\"");
        if (!t.description().isBlank()) {
            sb.append("\n").append(t.description());
        }
        return sb.toString();
    }
}
