package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.example.agent.engine.ContextCompactor;
import org.example.agent.tool.todo.PlanningState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompactToolTest {

    @Test
    void execute_replacesMessagesWithCompactedSummary(@TempDir Path tempDir) {
        var messages = new ArrayList<>(List.of(
                Message.user("original task"),
                Message.assistant("doing it")
        ));
        var tool = new CompactTool(
                new ContextCompactor(tempDir),
                () -> List.copyOf(messages),
                msgs -> { messages.clear(); messages.addAll(msgs); },
                PlanningState::new
        );

        var result = tool.execute(Map.of(), ToolUseContext.defaults("."));

        assertTrue(result.ok());
        assertEquals(1, messages.size());
        assertEquals(Role.USER, messages.get(0).role());
        var text = ((ContentBlock.Text) messages.get(0).content().get(0)).text();
        assertTrue(text.startsWith("This conversation was compacted for continuity."));
        assertTrue(text.contains("original task"));
    }

    @Test
    void compact_tool_is_not_concurrency_safe(@TempDir Path tempDir) {
        var tool = new CompactTool(
                new ContextCompactor(tempDir),
                List::of,
                msgs -> {},
                PlanningState::new
        );
        assertFalse(tool.isConcurrencySafe());
    }

    @Test
    void compact_tool_definition_has_expected_name(@TempDir Path tempDir) {
        var tool = new CompactTool(
                new ContextCompactor(tempDir),
                List::of,
                msgs -> {},
                PlanningState::new
        );
        assertEquals("compact", tool.definition().name());
    }
}
