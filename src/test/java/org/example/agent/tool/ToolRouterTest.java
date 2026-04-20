package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRouterTest {

    private ToolRegistry registryWithEcho() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "Echoes input", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success(String.valueOf(input.get("text")));
            }
        });
        return registry;
    }

    private final ToolUseContext ctx = ToolUseContext.defaults(".");

    @Test
    void routes_native_tool_and_returns_tool_result() {
        var router = new ToolRouter(registryWithEcho());
        var toolUse = new ContentBlock.ToolUse("id-1", "echo", Map.of("text", "hello"));
        var result = router.route(toolUse, ctx);
        assertEquals("id-1", result.toolUseId());
        assertEquals("hello", result.content());
    }

    @Test
    void throws_unknown_tool_exception_for_unregistered_tool() {
        var router = new ToolRouter(new ToolRegistry());
        var toolUse = new ContentBlock.ToolUse("id-2", "missing", Map.of());
        assertThrows(UnknownToolException.class, () -> router.route(toolUse, ctx));
    }

    @Test
    void throws_unsupported_operation_for_mcp_prefix() {
        var router = new ToolRouter(new ToolRegistry());
        var toolUse = new ContentBlock.ToolUse("id-3", "mcp__postgres__query", Map.of());
        assertThrows(UnsupportedOperationException.class, () -> router.route(toolUse, ctx));
    }
}
