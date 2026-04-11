package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolRegistry {

    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        tools.put(tool.definition().name(), tool);
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(Tool::definition).toList();
    }

    public ContentBlock.ToolResult execute(ContentBlock.ToolUse toolUse) {
        var tool = tools.get(toolUse.name());
        if (tool == null) {
            throw new UnknownToolException(toolUse.name());
        }
        var result = tool.execute(toolUse.input());
        return new ContentBlock.ToolResult(toolUse.id(), result);
    }
}
