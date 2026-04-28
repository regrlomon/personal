package org.example.agent.tool.background;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

public class BackgroundRunTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "background_run",
            "Start a shell command in the background. Returns a task_id immediately.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "command", Map.of("type", "string",
                                    "description", "Shell command to run (e.g. 'pytest -v')"),
                            "timeout", Map.of("type", "integer",
                                    "description", "Timeout in seconds (default 300)")
                    ),
                    "required", List.of("command")
            )
    );

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var raw = input.get("command");
        if (!(raw instanceof String command) || command.isBlank()) {
            return ToolResultEnvelope.error("command must not be blank");
        }
        int timeout = ((Number) input.getOrDefault("timeout", 300)).intValue();

        var manager = ctx.backgroundManager();
        if (manager == null) return ToolResultEnvelope.error("background manager not configured");

        var id = manager.submit(new ShellBackgroundTask(command, timeout));
        return ToolResultEnvelope.success("Started background task [" + id + "]: " + command);
    }
}
