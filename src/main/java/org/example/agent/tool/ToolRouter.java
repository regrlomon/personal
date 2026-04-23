package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.permission.PermissionBehavior;

import java.util.Objects;

public class ToolRouter {

    private final ToolRegistry registry;

    public ToolRouter(ToolRegistry registry) {
        this.registry = registry;
    }

    public ContentBlock.ToolResult route(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        var envelope = routeToEnvelope(toolUse, ctx);
        return new ContentBlock.ToolResult(toolUse.id(), envelope.content());
    }

    public ToolResultEnvelope routeToEnvelope(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        if (toolUse.name().startsWith("mcp__")) {
            throw new UnsupportedOperationException("MCP tools not implemented (s19)");
        }

        var checker = ctx.permissionChecker();
        if (checker != null) {
            var decision = checker.check(toolUse.name(), toolUse.input());
            if (decision.behavior() == PermissionBehavior.DENY) {
                return ToolResultEnvelope.error("Permission denied: " + decision.reason());
            }
            if (decision.behavior() == PermissionBehavior.ASK) {
                boolean approved = ctx.userConfirmation().confirm(
                        toolUse.name(), toolUse.input(), decision.reason());
                if (!approved) {
                    return ToolResultEnvelope.error("Permission denied by user");
                }
            }
            // ALLOW falls through to execution
        }

        var tool = registry.get(toolUse.name());
        if (tool == null) throw new UnknownToolException(toolUse.name());
        return tool.execute(toolUse.input(), ctx);
    }

    public boolean isConcurrencySafe(String toolName) {
        Objects.requireNonNull(toolName, "toolName must not be null");
        if (toolName.startsWith("mcp__")) return false;
        var tool = registry.get(toolName);
        return tool != null && tool.isConcurrencySafe();
    }
}
