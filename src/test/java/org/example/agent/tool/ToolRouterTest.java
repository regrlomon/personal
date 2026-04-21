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

    @Test
    void route_to_envelope_returns_envelope_for_native_tool() {
        var router = new ToolRouter(registryWithEcho());
        var toolUse = new ContentBlock.ToolUse("id-4", "echo", Map.of("text", "world"));
        var envelope = router.routeToEnvelope(toolUse, ctx);
        assertTrue(envelope.ok());
        assertEquals("world", envelope.content());
    }

    @Test
    void route_to_envelope_throws_unknown_tool_exception() {
        var router = new ToolRouter(new ToolRegistry());
        var toolUse = new ContentBlock.ToolUse("id-5", "missing", Map.of());
        assertThrows(UnknownToolException.class, () -> router.routeToEnvelope(toolUse, ctx));
    }

    @Test
    void route_to_envelope_throws_unsupported_for_mcp() {
        var router = new ToolRouter(new ToolRegistry());
        var toolUse = new ContentBlock.ToolUse("id-6", "mcp__db__query", Map.of());
        assertThrows(UnsupportedOperationException.class, () -> router.routeToEnvelope(toolUse, ctx));
    }

    @Test
    void is_concurrency_safe_returns_true_for_safe_tool() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() { return new ToolDefinition("safe_tool", "", Map.of()); }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("ok");
            }
            @Override
            public boolean isConcurrencySafe() { return true; }
        });
        assertTrue(new ToolRouter(registry).isConcurrencySafe("safe_tool"));
    }

    @Test
    void is_concurrency_safe_returns_false_for_unsafe_tool() {
        assertFalse(new ToolRouter(registryWithEcho()).isConcurrencySafe("echo"));
    }

    @Test
    void is_concurrency_safe_returns_false_for_unknown_tool() {
        assertFalse(new ToolRouter(new ToolRegistry()).isConcurrencySafe("nonexistent"));
    }

    @Test
    void is_concurrency_safe_returns_false_for_mcp_tool() {
        assertFalse(new ToolRouter(new ToolRegistry()).isConcurrencySafe("mcp__any__tool"));
    }
}
