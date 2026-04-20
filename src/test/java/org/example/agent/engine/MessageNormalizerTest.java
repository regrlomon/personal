package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageNormalizerTest {

    // --- Step 3: merge consecutive same-role messages ---

    @Test
    void passthrough_alternating_messages() {
        var messages = List.of(
                Message.user("hello"),
                Message.assistant("hi")
        );
        var result = MessageNormalizer.normalize(messages);
        assertEquals(2, result.size());
    }

    @Test
    void merges_consecutive_user_messages() {
        var messages = List.of(
                Message.user("part1"),
                Message.user("part2")
        );
        var result = MessageNormalizer.normalize(messages);
        assertEquals(1, result.size());
        assertEquals(Role.USER, result.get(0).role());
        assertEquals(2, result.get(0).content().size());
    }

    @Test
    void merges_tool_result_with_following_user_text() {
        var toolResult = new ContentBlock.ToolResult("id1", "file contents");
        var messages = List.of(
                new Message(Role.USER, List.of(toolResult)),
                Message.user("Please continue.")
        );
        var result = MessageNormalizer.normalize(messages);
        assertEquals(1, result.size());
        assertEquals(2, result.get(0).content().size());
    }

    // --- Step 2: fill in orphaned tool_use ---

    @Test
    void fills_orphaned_tool_use_with_cancelled_placeholder() {
        var toolUse = new ContentBlock.ToolUse("abc", "bash", Map.of("command", "ls"));
        var messages = List.of(
                new Message(Role.ASSISTANT, List.of(toolUse))
                // no tool_result follows
        );
        var result = MessageNormalizer.normalize(messages);
        assertEquals(2, result.size());
        var userMsg = result.get(1);
        assertEquals(Role.USER, userMsg.role());
        var block = (ContentBlock.ToolResult) userMsg.content().get(0);
        assertEquals("abc", block.toolUseId());
        assertEquals("(cancelled)", block.content());
    }

    @Test
    void does_not_fill_when_tool_result_already_present() {
        var toolUse = new ContentBlock.ToolUse("abc", "bash", Map.of("command", "ls"));
        var toolResult = new ContentBlock.ToolResult("abc", "output");
        var messages = List.of(
                new Message(Role.ASSISTANT, List.of(toolUse)),
                new Message(Role.USER, List.of(toolResult))
        );
        var result = MessageNormalizer.normalize(messages);
        assertEquals(2, result.size());
    }
}
