package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.QueryParams;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineBackgroundTest {

    @TempDir Path tempDir;

    @Test
    void background_tools_are_registered_and_available() {
        var registry = new ToolRegistry();
        var engine = new QueryEngine(req ->
                new ModelResponse(List.of(new ContentBlock.Text("done")),
                        StopReason.END_TURN, 10, 5),
                registry);
        var names = registry.definitions().stream()
                .map(d -> d.name()).toList();
        assertTrue(names.contains("background_run"));
        assertTrue(names.contains("background_check"));
        assertTrue(names.contains("background_list"));
        assertTrue(names.contains("background_cancel"));
    }

    @Test
    void engine_runs_successfully_after_background_run_tool_call() {
        var callCount = new AtomicInteger();
        var registry = new ToolRegistry();

        var engine = new QueryEngine(req -> {
            int turn = callCount.incrementAndGet();
            if (turn == 1) {
                return new ModelResponse(List.of(
                        new ContentBlock.ToolUse("t1", "background_run",
                                Map.of("command", "echo notify_test"))
                ), StopReason.TOOL_USE, 10, 5);
            }
            return new ModelResponse(
                    List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 10, 5);
        }, registry);

        var result = engine.run(new QueryParams(
                List.of(Message.user("start")), null, null, null, 3));
        assertTrue(result instanceof QueryResult.Success);
        assertTrue(callCount.get() >= 2);
    }
}
