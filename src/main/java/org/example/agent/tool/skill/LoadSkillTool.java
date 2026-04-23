package org.example.agent.tool.skill;

import org.example.agent.core.ToolDefinition;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;

import java.util.List;
import java.util.Map;

public class LoadSkillTool implements Tool {

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "load_skill",
            "Load the full content of a skill by name.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string", "description", "The skill name to load")
                    ),
                    "required", List.of("name")
            )
    );

    private final SkillRegistry registry;

    public LoadSkillTool(SkillRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ToolDefinition definition() { return DEFINITION; }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var raw = input.get("name");
        if (!(raw instanceof String name) || name.isBlank()) {
            return ToolResultEnvelope.error("name must be a non-blank string");
        }
        try {
            var body = registry.loadBody(name);
            return ToolResultEnvelope.success(
                    "<skill name=\"" + name + "\">\n" + body + "\n</skill>");
        } catch (IllegalArgumentException e) {
            return ToolResultEnvelope.error(e.getMessage());
        }
    }
}
