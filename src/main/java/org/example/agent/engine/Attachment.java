package org.example.agent.engine;

import org.example.agent.core.Message;

import java.util.Objects;

public record Attachment(String label, String content) {
    public Attachment {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }

    public Message toMessage() {
        return Message.user("[" + label + "]\n" + content);
    }
}
