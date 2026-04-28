package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessagePipelineTest {

    // --- ReminderMessage ---

    @Test
    void reminderMessage_toBlock_returnsTextBlock() {
        var reminder = new ReminderMessage("<reminder>refresh</reminder>");
        var block = reminder.toBlock();
        assertInstanceOf(ContentBlock.Text.class, block);
        assertEquals("<reminder>refresh</reminder>", block.text());
    }

    // --- Attachment ---

    @Test
    void attachment_toMessage_returnsUserMessage() {
        var attachment = new Attachment("memory", "some content");
        var msg = attachment.toMessage();
        assertEquals(Role.USER, msg.role());
        assertEquals(1, msg.content().size());
        var text = (ContentBlock.Text) msg.content().get(0);
        assertEquals("[memory]\nsome content", text.text());
    }
}
