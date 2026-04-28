package org.example.agent.engine;

import org.example.agent.core.ContentBlock;

import java.util.Objects;

public record ReminderMessage(String text) {
    public ReminderMessage {
        Objects.requireNonNull(text, "text must not be null");
    }

    public ContentBlock.Text toBlock() {
        return new ContentBlock.Text(text);
    }
}
