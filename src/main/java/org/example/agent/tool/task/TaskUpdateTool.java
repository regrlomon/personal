package org.example.agent.tool.task;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TaskUpdateTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "task_update",
            "Update a task. Only provided fields change. addBlockedBy/addBlocks append to existing dependency lists.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "id",           Map.of("type", "integer"),
                            "status",       Map.of("type", "string",
                                    "enum", List.of("pending", "in_progress", "completed", "deleted")),
                            "subject",      Map.of("type", "string"),
                            "description",  Map.of("type", "string"),
                            "owner",        Map.of("type", "string"),
                            "addBlockedBy", Map.of("type", "array", "items", Map.of("type", "integer")),
                            "addBlocks",    Map.of("type", "array", "items", Map.of("type", "integer"))
                    ),
                    "required", List.of("id")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        Object rawId = input.get("id");
        if (!(rawId instanceof Number)) return ToolResultEnvelope.error("id must be an integer");
        int id = ((Number) rawId).intValue();

        TaskStatus status = null;
        if (input.containsKey("status")) {
            status = switch ((String) input.get("status")) {
                case "pending"     -> TaskStatus.PENDING;
                case "in_progress" -> TaskStatus.IN_PROGRESS;
                case "completed"   -> TaskStatus.COMPLETED;
                case "deleted"     -> TaskStatus.DELETED;
                default            -> null;
            };
            if (status == null) return ToolResultEnvelope.error("Unknown status: " + input.get("status"));
        }

        String subject     = (String) input.get("subject");
        String description = (String) input.get("description");
        String owner       = (String) input.get("owner");
        List<Integer> addBlockedBy = toIntList((List<Object>) input.getOrDefault("addBlockedBy", List.of()));
        List<Integer> addBlocks    = toIntList((List<Object>) input.getOrDefault("addBlocks",    List.of()));

        var taskManager = ctx.taskManager();
        if (taskManager == null) return ToolResultEnvelope.error("task manager not configured");

        try {
            var result = taskManager.update(id,
                    new TaskPatch(status, subject, description, owner, addBlockedBy, addBlocks));
            if (!result.unblocked().isEmpty()) {
                return ToolResultEnvelope.success(
                        "Completed task #" + id + "; unblocked: " + result.unblocked());
            }
            return ToolResultEnvelope.success("Updated task #" + id);
        } catch (NoSuchTaskException e) {
            return ToolResultEnvelope.error(e.getMessage());
        } catch (UncheckedIOException e) {
            return ToolResultEnvelope.error("IO error: " + e.getCause().getMessage());
        }
    }

    private static List<Integer> toIntList(List<Object> raw) {
        return raw.stream().map(o -> ((Number) o).intValue()).collect(Collectors.toList());
    }
}
