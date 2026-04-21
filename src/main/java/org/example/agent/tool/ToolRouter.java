package org.example.agent.tool;

import org.example.agent.core.ContentBlock;

public class ToolRouter {

    private final ToolRegistry registry;

    public ToolRouter(ToolRegistry registry) {
        this.registry = registry;
    }

    public ContentBlock.ToolResult route(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        if (toolUse.name().startsWith("mcp__")) return routeMcp(toolUse, ctx);
        return routeNative(toolUse, ctx);
    }

    private ContentBlock.ToolResult routeNative(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        var tool = registry.get(toolUse.name());
        if (tool == null) throw new UnknownToolException(toolUse.name());
        var envelope = tool.execute(toolUse.input(), ctx);
        return new ContentBlock.ToolResult(toolUse.id(), envelope.content());
    }

    private ContentBlock.ToolResult routeMcp(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        throw new UnsupportedOperationException("MCP tools not implemented (s19)");
    }

    public ToolResultEnvelope routeToEnvelope(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        if (toolUse.name().startsWith("mcp__")) {
            throw new UnsupportedOperationException("MCP tools not implemented (s19)");
        }
        var tool = registry.get(toolUse.name());
        if (tool == null) throw new UnknownToolException(toolUse.name());
        return tool.execute(toolUse.input(), ctx);
    }

    public boolean isConcurrencySafe(String toolName) {
        if (toolName.startsWith("mcp__")) return false;
        var tool = registry.get(toolName);
        return tool != null && tool.isConcurrencySafe();
    }
}
