package org.example.agent.tool.task;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TaskCreateTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "task_create",
            "Create a new task. Optionally declare dependencies via blockedBy.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "subject", Map.of("type", "string", "description", "One-line task title"),
                            "description", Map.of("type", "string", "description", "Additional details"),
                            "blockedBy", Map.of("type", "array", "items", Map.of("type", "integer"),
                                    "description", "IDs of tasks that must complete first")
                    ),
                    "required", List.of("subject")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        Object rawSubject = input.get("subject");
        if (!(rawSubject instanceof String subject) || subject.isBlank()) {
            return ToolResultEnvelope.error("subject must not be blank");
        }
        String description = (String) input.getOrDefault("description", "");
        if (description == null) description = "";

        List<Integer> blockedBy;
        try {
            var raw = (List<Object>) input.getOrDefault("blockedBy", List.of());
            blockedBy = raw.stream().map(o -> ((Number) o).intValue()).collect(Collectors.toList());
        } catch (ClassCastException e) {
            return ToolResultEnvelope.error("blockedBy must be an array of integers");
        }

        var taskManager = ctx.taskManager();
        if (taskManager == null) return ToolResultEnvelope.error("task manager not configured");

        try {
            var task = taskManager.create(subject, description, blockedBy);
            return ToolResultEnvelope.success("Created task #" + task.id() + ": " + task.subject());
        } catch (NoSuchTaskException e) {
            return ToolResultEnvelope.error(e.getMessage());
        } catch (UncheckedIOException e) {
            return ToolResultEnvelope.error("IO error: " + e.getCause().getMessage());
        }
    }
}
