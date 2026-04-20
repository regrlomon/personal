package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRegistryTest {

    private Tool echoTool() {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "Echoes input", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success(String.valueOf(input.get("text")));
            }
        };
    }

    @Test
    void definitions_returns_registered_tool_definitions() {
        var registry = new ToolRegistry();
        registry.register(echoTool());
        assertEquals(1, registry.definitions().size());
        assertEquals("echo", registry.definitions().get(0).name());
    }

    @Test
    void get_returns_registered_tool_by_name() {
        var registry = new ToolRegistry();
        var tool = echoTool();
        registry.register(tool);
        assertSame(tool, registry.get("echo"));
    }

    @Test
    void get_returns_null_for_unknown_tool() {
        var registry = new ToolRegistry();
        assertNull(registry.get("nonexistent"));
    }

    @Test
    void register_multiple_tools_all_appear_in_definitions() {
        var registry = new ToolRegistry();
        registry.register(echoTool());
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("noop", "Does nothing", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("ok");
            }
        });
        assertEquals(2, registry.definitions().size());
    }
}
