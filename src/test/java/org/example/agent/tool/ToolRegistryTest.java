package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
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
            public String execute(Map<String, Object> input) {
                return String.valueOf(input.get("text"));
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
    void execute_known_tool_returns_tool_result_block() {
        var registry = new ToolRegistry();
        registry.register(echoTool());

        var toolUse = new ContentBlock.ToolUse("call-1", "echo", Map.of("text", "hello"));
        var result = registry.execute(toolUse);

        assertEquals("call-1", result.toolUseId());
        assertEquals("hello", result.content());
    }

    @Test
    void execute_unknown_tool_throws_unknown_tool_exception() {
        var registry = new ToolRegistry();
        var toolUse = new ContentBlock.ToolUse("call-2", "nonexistent", Map.of());

        var ex = assertThrows(UnknownToolException.class, () -> registry.execute(toolUse));
        assertTrue(ex.getMessage().contains("nonexistent"));
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
            public String execute(Map<String, Object> input) {
                return "ok";
            }
        });
        assertEquals(2, registry.definitions().size());
    }
}
