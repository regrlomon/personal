package org.example.agent.tool.subagent;

import org.example.agent.core.*;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubagentToolTest {

    @Test
    void definition_has_correct_name() {
        var tool = new SubagentTool(req -> null, new ToolRegistry(), 5);
        assertEquals("subagent", tool.definition().name());
    }

    @Test
    void is_not_concurrency_safe() {
        var tool = new SubagentTool(req -> null, new ToolRegistry(), 5);
        assertFalse(tool.isConcurrencySafe());
    }

    @Test
    void execute_returns_error_when_prompt_missing() {
        var tool = new SubagentTool(req -> null, new ToolRegistry(), 5);
        var result = tool.execute(Map.of(), ToolUseContext.defaults("."));
        assertFalse(result.ok());
        assertTrue(result.isError());
    }

    @Test
    void execute_returns_last_assistant_message_from_subagent() {
        var tool = new SubagentTool(
                request -> new ModelResponse(
                        List.of(new ContentBlock.Text("subagent result")),
                        StopReason.END_TURN, 10, 5),
                new ToolRegistry(),
                5
        );
        var result = tool.execute(Map.of("prompt", "do something"), ToolUseContext.defaults("."));
        assertTrue(result.ok());
        assertEquals("subagent result", result.content());
    }

    @Test
    void execute_returns_last_message_after_subagent_uses_tools() {
        var subRegistry = new ToolRegistry();
        subRegistry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("search", "", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("found result");
            }
        });

        var responses = new ModelResponse[]{
                new ModelResponse(
                        List.of(new ContentBlock.ToolUse("id-1", "search", Map.of())),
                        StopReason.TOOL_USE, 10, 5),
                new ModelResponse(
                        List.of(new ContentBlock.Text("search complete")),
                        StopReason.END_TURN, 10, 5)
        };
        var idx = new int[]{0};

        var tool = new SubagentTool(request -> responses[idx[0]++], subRegistry, 5);
        var result = tool.execute(Map.of("prompt", "search for something"), ToolUseContext.defaults("."));

        assertTrue(result.ok());
        assertEquals("search complete", result.content());
    }
}
