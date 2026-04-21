package org.example.agent.tool.task;

import org.example.agent.core.*;
import org.example.agent.engine.QueryEngine;
import org.example.agent.engine.QueryResult;
import org.example.agent.model.ModelClient;
import org.example.agent.tool.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TaskTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "task",
            "Run a subtask in a clean context and return a summary.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "prompt", Map.of("type", "string", "description", "The task to perform")
                    ),
                    "required", List.of("prompt")
            )
    );

    private final ModelClient modelClient;
    private final ToolRegistry subRegistry;
    private final int maxTurns;

    public TaskTool(ModelClient modelClient, ToolRegistry subRegistry, int maxTurns) {
        this.modelClient = modelClient;
        this.subRegistry = subRegistry;
        this.maxTurns = maxTurns;
    }

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var prompt = (String) input.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return ToolResultEnvelope.error("prompt is required");
        }

        var params = new QueryParams(
                List.of(Message.user(prompt)),
                null, null, null,
                maxTurns
        );

        var result = new QueryEngine(modelClient, subRegistry).run(params);

        return switch (result) {
            case QueryResult.Failed f ->
                    ToolResultEnvelope.error(f.cause().getMessage());
            case QueryResult.Success s -> {
                var summary = s.messages().stream()
                        .filter(m -> m.role() == Role.ASSISTANT)
                        .reduce((a, b) -> b)
                        .map(m -> m.content().stream()
                                .filter(b -> b instanceof ContentBlock.Text)
                                .map(b -> ((ContentBlock.Text) b).text())
                                .collect(Collectors.joining()))
                        .filter(text -> !text.isBlank())
                        .orElse(null);
                if (summary == null) {
                    yield ToolResultEnvelope.error("subagent produced no output");
                }
                yield ToolResultEnvelope.success(summary);
            }
        };
    }
}
