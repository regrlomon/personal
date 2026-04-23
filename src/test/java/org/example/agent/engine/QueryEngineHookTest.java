package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.hook.*;
import org.example.agent.model.*;
import org.example.agent.tool.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineHookTest {

    private static ModelClient endTurnModel() {
        return req -> new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 0, 0);
    }

    private static ModelClient toolThenEndModel(String toolName, Map<String, Object> input) {
        int[] count = {0};
        return req -> {
            count[0]++;
            if (count[0] == 1)
                return new ModelResponse(
                        List.of(new ContentBlock.ToolUse("tid1", toolName, input)),
                        StopReason.TOOL_USE, 0, 0);
            return new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 0, 0);
        };
    }

    private static ToolRegistry registryWithEcho(String toolName) {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition(toolName, "", Map.of());
            }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("echo result");
            }
        });
        return registry;
    }

    @Test
    void session_start_hook_is_called_before_first_model_call() {
        var called = new AtomicBoolean(false);
        HookRunner hook = event -> {
            if (event.name() == HookEventName.SESSION_START) called.set(true);
            return HookResult.ok();
        };

        var engine = new QueryEngine(endTurnModel(), new ToolRegistry(), hook);
        engine.run(new QueryParams(List.of(Message.user("hi")), null, null, null, 5));

        assertTrue(called.get(), "SessionStart hook must be called");
    }

    @Test
    void session_start_hook_receives_system_prompt_in_payload() {
        List<Map<String, Object>> payloads = new ArrayList<>();
        HookRunner hook = event -> {
            if (event.name() == HookEventName.SESSION_START) payloads.add(event.payload());
            return HookResult.ok();
        };

        var engine = new QueryEngine(endTurnModel(), new ToolRegistry(), hook);
        engine.run(new QueryParams(List.of(Message.user("hi")), "my-system-prompt", null, null, 5));

        assertFalse(payloads.isEmpty());
        assertEquals("my-system-prompt", payloads.get(0).get("system_prompt"));
    }

    @Test
    void post_tool_exit_2_injection_message_appears_in_conversation() {
        HookRunner hook = event -> {
            if (event.name() == HookEventName.POST_TOOL_USE) return HookResult.inject("hook says: noted");
            return HookResult.ok();
        };

        var registry = registryWithEcho("bash");
        var engine = new QueryEngine(toolThenEndModel("bash", Map.of()), registry, hook);
        var result = (QueryResult.Success) engine.run(
                new QueryParams(List.of(Message.user("run bash")), null, null, null, 5));

        var userTexts = result.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .toList();

        assertTrue(userTexts.stream().anyMatch(t -> t.contains("hook says: noted")),
                "Injection message should appear as user message. Texts: " + userTexts);
    }

    @Test
    void no_hook_runner_runs_normally_without_error() {
        var engine = new QueryEngine(endTurnModel(), new ToolRegistry());
        var result = engine.run(new QueryParams(List.of(Message.user("hi")), null, null, null, 5));
        assertInstanceOf(QueryResult.Success.class, result);
    }
}
