package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.*;
import org.example.agent.tool.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineReminderTest {

    private ModelResponse toolResponse(String toolId, String toolName) {
        return new ModelResponse(
                List.of(new ContentBlock.ToolUse(toolId, toolName, Map.of())),
                StopReason.TOOL_USE, 10, 5);
    }

    private ModelResponse endResponse() {
        return new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 10, 5);
    }

    private ToolRegistry readFileRegistry() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("read_file", "", Map.of()); }
            @Override public boolean isConcurrencySafe() { return true; }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("content");
            }
        });
        return registry;
    }

    // --- RED test: verifies reminders are ephemeral (not persisted to state) ---
    // FAILS with old code (reminder IS in state); PASSES after refactor.

    @Test
    void reminder_not_persisted_to_state() {
        var responses = List.of(
                toolResponse("1", "read_file"),
                toolResponse("2", "read_file"),
                toolResponse("3", "read_file"),
                endResponse()
        );
        var idx = new AtomicInteger(0);
        var engine = new QueryEngine(request -> responses.get(idx.getAndIncrement()), readFileRegistry());

        var result = (QueryResult.Success) engine.run(
                new QueryParams(List.of(Message.user("do things")), null, null, null, null));

        // Reminders are ephemeral: they must NOT appear in the persisted state messages.
        var reminderInState = result.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .anyMatch(m -> m.content().stream().anyMatch(
                        b -> b instanceof ContentBlock.Text t && t.text().contains("<reminder>")));
        assertFalse(reminderInState, "reminder must NOT be persisted to state — it is ephemeral");
    }

    // --- Contract test: reminder still reaches the model via the pipeline ---

    @Test
    void reminder_sent_to_model_after_three_rounds_without_todo() {
        var responses = List.of(
                toolResponse("1", "read_file"),
                toolResponse("2", "read_file"),
                toolResponse("3", "read_file"),
                endResponse()
        );
        var idx = new AtomicInteger(0);
        var capturedRequests = new ArrayList<ModelRequest>();

        var engine = new QueryEngine(request -> {
            capturedRequests.add(request);
            return responses.get(idx.getAndIncrement());
        }, readFileRegistry());

        engine.run(new QueryParams(List.of(Message.user("do things")), null, null, null, null));

        var hasReminder = capturedRequests.stream()
                .flatMap(r -> r.messages().stream())
                .filter(m -> m.role() == Role.USER)
                .anyMatch(m -> m.content().stream().anyMatch(
                        b -> b instanceof ContentBlock.Text t && t.text().contains("<reminder>")));
        assertTrue(hasReminder, "reminder must appear in a model request after 3 stale rounds");
    }

    @Test
    void reminder_not_sent_when_todo_called_each_round() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("todo", "", Map.of()); }
            @Override public boolean isConcurrencySafe() { return false; }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                ctx.planningState().update(List.of());
                return ToolResultEnvelope.success("[]");
            }
        });

        var responses = List.of(
                toolResponse("1", "todo"),
                toolResponse("2", "todo"),
                toolResponse("3", "todo"),
                endResponse()
        );
        var idx = new AtomicInteger(0);
        var capturedRequests = new ArrayList<ModelRequest>();

        var engine = new QueryEngine(request -> {
            capturedRequests.add(request);
            return responses.get(idx.getAndIncrement());
        }, registry);

        engine.run(new QueryParams(List.of(Message.user("plan things")), null, null, null, null));

        var hasReminder = capturedRequests.stream()
                .flatMap(r -> r.messages().stream())
                .filter(m -> m.role() == Role.USER)
                .anyMatch(m -> m.content().stream().anyMatch(
                        b -> b instanceof ContentBlock.Text t && t.text().contains("<reminder>")));
        assertFalse(hasReminder, "no reminder expected when todo is called each round");
    }
}
