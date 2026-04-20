package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.Tool;
import org.example.agent.tool.ToolRegistry;
import org.example.agent.tool.ToolResultEnvelope;
import org.example.agent.tool.ToolUseContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineTest {

    private QueryParams params(String userMessage) {
        return new QueryParams(
                List.of(Message.user(userMessage)),
                "You are helpful.",
                null, null, null
        );
    }

    // ---------------------------------------------------------------
    // END_TURN: model responds and finishes immediately
    // ---------------------------------------------------------------

    @Test
    void end_turn_returns_success_with_final_assistant_message() {
        var registry = new ToolRegistry();
        var callCount = new int[]{0};

        var engine = new QueryEngine(
                request -> {
                    callCount[0]++;
                    return new ModelResponse(
                            List.of(new ContentBlock.Text("Hello!")),
                            StopReason.END_TURN,
                            10, 5
                    );
                },
                registry
        );

        var result = engine.run(params("Hi"));

        assertInstanceOf(QueryResult.Success.class, result);
        var success = (QueryResult.Success) result;
        assertEquals(1, success.totalTurns());
        assertEquals(1, callCount[0]);
        // messages: original user + final assistant
        assertEquals(2, success.messages().size());
        assertEquals(Role.ASSISTANT, success.messages().get(1).role());
    }

    // ---------------------------------------------------------------
    // TOOL_USE: model calls a tool, engine executes it, continues
    // ---------------------------------------------------------------

    @Test
    void tool_use_executes_tool_appends_result_and_continues() {
        var toolUse = new ContentBlock.ToolUse("call-1", "greet", Map.of("name", "World"));

        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("greet", "Greet someone", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("Hello, " + input.get("name") + "!");
            }
        });

        // Call 1: TOOL_USE  →  Call 2: END_TURN
        var responses = new ModelResponse[]{
                new ModelResponse(List.of(toolUse), StopReason.TOOL_USE, 10, 5),
                new ModelResponse(List.of(new ContentBlock.Text("Done.")), StopReason.END_TURN, 20, 5)
        };
        var idx = new int[]{0};

        var engine = new QueryEngine(request -> responses[idx[0]++], registry);
        var result = engine.run(params("Greet the world"));

        assertInstanceOf(QueryResult.Success.class, result);
        var success = (QueryResult.Success) result;
        assertEquals(2, success.totalTurns());
        assertEquals(2, idx[0]);
        // messages: user → assistant(tooluse) → user(toolresult) → assistant("Done.")
        assertEquals(4, success.messages().size());
        assertEquals(Role.USER, success.messages().get(2).role());
        assertInstanceOf(ContentBlock.ToolResult.class, success.messages().get(2).content().get(0));
        var toolResult = (ContentBlock.ToolResult) success.messages().get(2).content().get(0);
        assertEquals("Hello, World!", toolResult.content());
    }

    // ---------------------------------------------------------------
    // MAX_TOKENS: output truncated, engine injects continue prompt
    // ---------------------------------------------------------------

    @Test
    void max_tokens_appends_continue_prompt_and_resumes() {
        var registry = new ToolRegistry();

        // Call 1: MAX_TOKENS  →  Call 2: END_TURN
        var responses = new ModelResponse[]{
                new ModelResponse(List.of(new ContentBlock.Text("Part 1...")), StopReason.MAX_TOKENS, 10, 100),
                new ModelResponse(List.of(new ContentBlock.Text("...Part 2.")), StopReason.END_TURN, 10, 20)
        };
        var idx = new int[]{0};

        var engine = new QueryEngine(request -> responses[idx[0]++], registry);
        var result = engine.run(params("Write a long essay"));

        assertInstanceOf(QueryResult.Success.class, result);
        var success = (QueryResult.Success) result;
        assertEquals(2, success.totalTurns());
        // messages: user → assistant(part1) → user("Please continue.") → assistant(part2)
        assertEquals(4, success.messages().size());
        assertEquals(Role.USER, success.messages().get(2).role());
        var continueBlock = (ContentBlock.Text) success.messages().get(2).content().get(0);
        assertEquals("Please continue.", continueBlock.text());
    }

    // ---------------------------------------------------------------
    // STOP_SEQUENCE: treated as terminal, same as END_TURN
    // ---------------------------------------------------------------

    @Test
    void stop_sequence_returns_success_immediately() {
        var registry = new ToolRegistry();
        var engine = new QueryEngine(
                request -> new ModelResponse(
                        List.of(new ContentBlock.Text("Stopped.")),
                        StopReason.STOP_SEQUENCE,
                        5, 3
                ),
                registry
        );

        var result = engine.run(params("Go"));
        assertInstanceOf(QueryResult.Success.class, result);
        assertEquals(1, ((QueryResult.Success) result).totalTurns());
    }

    // ---------------------------------------------------------------
    // UNKNOWN TOOL: engine catches UnknownToolException and returns error ToolResult
    // ---------------------------------------------------------------

    @Test
    void unknown_tool_returns_error_result_and_continues() {
        var registry = new ToolRegistry();
        // registry is empty — no tools registered

        var toolUse = new ContentBlock.ToolUse("call-x", "nonexistent_tool", Map.of());
        var responses = new ModelResponse[]{
                new ModelResponse(List.of(toolUse), StopReason.TOOL_USE, 10, 5),
                new ModelResponse(List.of(new ContentBlock.Text("Done.")), StopReason.END_TURN, 10, 5)
        };
        var idx = new int[]{0};

        var engine = new QueryEngine(request -> responses[idx[0]++], registry);
        var result = engine.run(params("call a tool"));

        assertInstanceOf(QueryResult.Success.class, result);
        var success = (QueryResult.Success) result;
        // messages: user → assistant(tooluse) → user(error toolresult) → assistant("Done.")
        assertEquals(4, success.messages().size());
        var toolResult = (ContentBlock.ToolResult) success.messages().get(2).content().get(0);
        assertTrue(toolResult.content().contains("nonexistent_tool"));
    }
}
