package org.example.agent.tool.todo;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

public class TodoTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "todo",
            "Update the session plan with current task status. Rewrite the full list each call.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "items", Map.of(
                                    "type", "array",
                                    "description", "The complete list of plan items",
                                    "items", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "content", Map.of("type", "string"),
                                                    "status", Map.of("type", "string",
                                                            "enum", List.of("pending", "in_progress", "completed")),
                                                    "activeForm", Map.of("type", "string")
                                            ),
                                            "required", List.of("content", "status")
                                    )
                            )
                    ),
                    "required", List.of("items")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public boolean isConcurrencySafe() { return false; }

    @Override
    @SuppressWarnings("unchecked")
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        try {
            var rawItems = (List<Map<String, Object>>) input.get("items");
            if (rawItems == null) {
                return ToolResultEnvelope.error("items field is required");
            }
            List<PlanItem> planItems = rawItems.stream()
                    .map(this::toPlanItem)
                    .toList();
            ctx.planningState().update(planItems);
        } catch (IllegalArgumentException e) {
            return ToolResultEnvelope.error(e.getMessage());
        }
        return ToolResultEnvelope.success(ctx.planningState().render());
    }

    private PlanItem toPlanItem(Map<String, Object> raw) {
        String content = (String) raw.get("content");
        String statusStr = (String) raw.get("status");
        String activeForm = raw.getOrDefault("activeForm", "").toString();
        PlanStatus status = switch (statusStr) {
            case "pending" -> PlanStatus.PENDING;
            case "in_progress" -> PlanStatus.IN_PROGRESS;
            case "completed" -> PlanStatus.COMPLETED;
            default -> throw new IllegalArgumentException("Unknown status: " + statusStr);
        };
        return new PlanItem(content, status, activeForm);
    }
}
