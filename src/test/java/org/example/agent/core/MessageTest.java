package org.example.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void user_factory_creates_user_message_with_text_block() {
        var msg = Message.user("hello");
        assertEquals(Role.USER, msg.role());
        assertEquals(1, msg.content().size());
        assertInstanceOf(ContentBlock.Text.class, msg.content().get(0));
        assertEquals("hello", ((ContentBlock.Text) msg.content().get(0)).text());
    }

    @Test
    void assistant_factory_creates_assistant_message_with_text_block() {
        var msg = Message.assistant("hi");
        assertEquals(Role.ASSISTANT, msg.role());
        assertEquals(1, msg.content().size());
        assertInstanceOf(ContentBlock.Text.class, msg.content().get(0));
        assertEquals("hi", ((ContentBlock.Text) msg.content().get(0)).text());
    }

    @Test
    void content_block_tool_use_holds_fields() {
        var toolUse = new ContentBlock.ToolUse("id-1", "my_tool", Map.of("key", "val"));
        assertEquals("id-1", toolUse.id());
        assertEquals("my_tool", toolUse.name());
        assertEquals("val", toolUse.input().get("key"));
    }

    @Test
    void content_block_tool_result_holds_fields() {
        var result = new ContentBlock.ToolResult("id-1", "output text");
        assertEquals("id-1", result.toolUseId());
        assertEquals("output text", result.content());
    }

    @Test
    void message_with_multiple_blocks() {
        var blocks = List.<ContentBlock>of(
            new ContentBlock.Text("thinking..."),
            new ContentBlock.ToolUse("id-2", "calc", Map.of("x", 1))
        );
        var msg = new Message(Role.ASSISTANT, blocks);
        assertEquals(2, msg.content().size());
    }
}
