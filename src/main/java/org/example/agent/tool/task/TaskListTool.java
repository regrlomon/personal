package org.example.agent.tool.task;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

public class TaskListTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "task_list",
            "List all tasks. Ready tasks (pending with no blockedBy) are marked with *.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(),
                    "required", List.of()
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var taskManager = ctx.taskManager();
        if (taskManager == null) return ToolResultEnvelope.error("task manager not configured");

        try {
            var tasks = taskManager.list().stream()
                    .filter(t -> t.status() != TaskStatus.DELETED)
                    .toList();
            if (tasks.isEmpty()) return ToolResultEnvelope.success("(no tasks)");

            var sb = new StringBuilder();
            for (var t : tasks) {
                String statusLabel = t.status().name().toLowerCase();
                if (taskManager.isReady(t)) statusLabel += "*";
                sb.append(String.format("#%-3d [%-13s] %s", t.id(), statusLabel, t.subject()));
                if (!t.owner().isBlank()) sb.append("    owner: ").append(t.owner());
                sb.append("\n");
            }
            return ToolResultEnvelope.success(sb.toString().stripTrailing());
        } catch (UncheckedIOException e) {
            return ToolResultEnvelope.error("IO error: " + e.getCause().getMessage());
        }
    }
}
