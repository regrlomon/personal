package org.example.agent.engine;

import org.example.agent.core.Message;

public record Attachment(String label, String content) {
    public Message toMessage() {
        return Message.user("[" + label + "]\n" + content);
    }
}
