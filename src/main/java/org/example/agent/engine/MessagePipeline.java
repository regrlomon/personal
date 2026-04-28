package org.example.agent.engine;

import org.example.agent.core.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class MessagePipeline {

    public List<Message> build(List<Message> raw,
                               List<Attachment> attachments,
                               List<ReminderMessage> reminders) {
        Objects.requireNonNull(raw, "raw must not be null");
        Objects.requireNonNull(attachments, "attachments must not be null");
        Objects.requireNonNull(reminders, "reminders must not be null");
        var messages = normalize(raw);
        messages = prependAttachments(messages, attachments);
        messages = appendReminders(messages, reminders);
        return messages;
    }

    private List<Message> normalize(List<Message> raw) {
        return MessageNormalizer.normalize(raw);
    }

    private List<Message> prependAttachments(List<Message> messages,
                                             List<Attachment> attachments) {
        // Attachments are prepended after normalization so they remain distinct messages
        // and are not merged with the first user turn by normalize().
        if (attachments.isEmpty()) return messages;
        var result = new ArrayList<Message>();
        attachments.forEach(a -> result.add(a.toMessage()));
        result.addAll(messages);
        return List.copyOf(result);
    }

    private List<Message> appendReminders(List<Message> messages,
                                          List<ReminderMessage> reminders) {
        if (reminders.isEmpty() || messages.isEmpty()) return messages;
        // Precondition: last message is always USER in a well-formed conversation.
        // normalize() guarantees alternating roles; the final message before a model
        // call is always the user turn.
        var last = messages.get(messages.size() - 1);
        var blocks = new ArrayList<>(last.content());
        reminders.forEach(r -> blocks.add(r.toBlock()));
        var updated = new Message(last.role(), List.copyOf(blocks));
        var result = new ArrayList<>(messages.subList(0, messages.size() - 1));
        result.add(updated);
        return List.copyOf(result);
    }
}
