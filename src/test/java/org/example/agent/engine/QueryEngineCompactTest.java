package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineCompactTest {

    private QueryParams params(String msg) {
        return new QueryParams(List.of(Message.user(msg)), null, null, null, null);
    }

    @Test
    void first_max_tokens_triggers_compact_and_retries(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var registry = new ToolRegistry();
        var callCount = new int[]{0};

        var responses = new ModelResponse[]{
                new ModelResponse(List.of(new ContentBlock.Text("Part 1...")),
                        StopReason.MAX_TOKENS, 10, 100),
                new ModelResponse(List.of(new ContentBlock.Text("Done.")),
                        StopReason.END_TURN, 10, 20)
        };

        var engine = new QueryEngine(
                request -> responses[callCount[0]++],
                registry,
                compactor,
                Executors.newSingleThreadExecutor()
        );

        var result = engine.run(params("Write something long"));

        assertInstanceOf(QueryResult.Success.class, result);
        assertEquals(2, callCount[0]);

        var messages = ((QueryResult.Success) result).messages();
        assertEquals(2, messages.size());
        assertEquals(Role.USER, messages.get(0).role());
        var compactedText = ((ContentBlock.Text) messages.get(0).content().get(0)).text();
        assertTrue(compactedText.startsWith("This conversation was compacted for continuity."));
    }

    @Test
    void max_tokens_after_compact_falls_back_to_continue_prompt(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var registry = new ToolRegistry();
        var callCount = new int[]{0};

        var responses = new ModelResponse[]{
                new ModelResponse(List.of(new ContentBlock.Text("P1")),
                        StopReason.MAX_TOKENS, 10, 100),
                new ModelResponse(List.of(new ContentBlock.Text("P2")),
                        StopReason.MAX_TOKENS, 10, 100),
                new ModelResponse(List.of(new ContentBlock.Text("Done.")),
                        StopReason.END_TURN, 10, 5)
        };

        var engine = new QueryEngine(
                request -> responses[callCount[0]++],
                registry,
                compactor,
                Executors.newSingleThreadExecutor()
        );

        var result = engine.run(params("Write something very long"));

        assertInstanceOf(QueryResult.Success.class, result);
        assertEquals(3, callCount[0]);

        var messages = ((QueryResult.Success) result).messages();
        assertEquals(4, messages.size());
        var continueMsg = (ContentBlock.Text) messages.get(2).content().get(0);
        assertEquals("Please continue.", continueMsg.text());
    }
}
