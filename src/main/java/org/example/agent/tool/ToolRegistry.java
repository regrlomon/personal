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

    public Tool get(String name) {
        return tools.get(name);
    }

    public ContentBlock.ToolResult execute(ContentBlock.ToolUse toolUse) {
        var ctx = ToolUseContext.defaults(System.getProperty("user.dir"));
        var tool = tools.get(toolUse.name());
        if (tool == null) {
            return new ContentBlock.ToolResult(toolUse.id(), "Unknown tool: " + toolUse.name());
        }
        var envelope = tool.execute(toolUse.input(), ctx);
        return new ContentBlock.ToolResult(toolUse.id(), envelope.content());
    }
}
