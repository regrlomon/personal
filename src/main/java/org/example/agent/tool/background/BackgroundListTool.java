package org.example.agent.tool.background;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.Map;

public class BackgroundListTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "background_list",
            "List all background tasks and their current status.",
            Map.of("type", "object", "properties", Map.of())
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var manager = ctx.backgroundManager();
        if (manager == null) return ToolResultEnvelope.error("background manager not configured");

        var records = manager.list();
        if (records.isEmpty()) return ToolResultEnvelope.success("no background tasks");

        var sb = new StringBuilder();
        for (var r : records) {
            long elapsedSec = (System.currentTimeMillis() - r.startedAt()) / 1000;
            String marker = switch (r.status()) {
                case COMPLETED -> "✓";
                case FAILED, TIMEOUT -> "✗";
                case CANCELLED -> "✕";
                default -> " ";
            };
            sb.append(String.format("[%s] %-10s %-30s (%ds) %s%n",
                    r.id(), r.status(), r.description(), elapsedSec, marker));
        }
        return ToolResultEnvelope.success(sb.toString().trim());
    }
}
