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

    // --- normalize (delegates to MessageNormalizer) ---

    @Test
    void build_normalize_mergesConsecutiveUserMessages() {
        var pipeline = new MessagePipeline();
        var raw = List.of(Message.user("a"), Message.user("b"));
        var result = pipeline.build(raw, List.of(), List.of());
        assertEquals(1, result.size(), "consecutive USER messages must be merged");
    }

    // --- prependAttachments ---

    @Test
    void build_emptyAttachments_messagesUnchanged() {
        var pipeline = new MessagePipeline();
        var raw = List.of(Message.user("hello"));
        var result = pipeline.build(raw, List.of(), List.of());
        assertEquals(1, result.size());
        var text = (ContentBlock.Text) result.get(0).content().get(0);
        assertEquals("hello", text.text());
    }

    @Test
    void build_attachmentsPrependedBeforeRawMessages() {
        var pipeline = new MessagePipeline();
        var raw = List.of(Message.user("query"));
        var attachments = List.of(new Attachment("ctx", "extra info"));
        var result = pipeline.build(raw, attachments, List.of());
        assertEquals(2, result.size());
        // first message is the attachment
        var first = (ContentBlock.Text) result.get(0).content().get(0);
        assertEquals("[ctx]\nextra info", first.text());
        // second message is the original query
        var second = (ContentBlock.Text) result.get(1).content().get(0);
        assertEquals("query", second.text());
    }

    // --- appendReminders ---

    @Test
    void build_emptyReminders_messagesUnchanged() {
        var pipeline = new MessagePipeline();
        var raw = List.of(Message.user("hi"));
        var result = pipeline.build(raw, List.of(), List.of());
        assertEquals(1, result.size());
        assertEquals(1, result.get(0).content().size());
    }

    @Test
    void build_reminderAppendedToLastMessage() {
        var pipeline = new MessagePipeline();
        var raw = List.of(
                Message.user("turn 1"),
                Message.assistant("reply"),
                Message.user("turn 2")          // last message — USER
        );
        var reminders = List.of(new ReminderMessage("<reminder>refresh</reminder>"));
        var result = pipeline.build(raw, List.of(), reminders);
        // message count unchanged
        assertEquals(3, result.size());
        // last message now has 2 blocks: original text + reminder
        var last = result.get(2);
        assertEquals(Role.USER, last.role());
        assertEquals(2, last.content().size());
        var reminderBlock = (ContentBlock.Text) last.content().get(1);
        assertEquals("<reminder>refresh</reminder>", reminderBlock.text());
    }
}
