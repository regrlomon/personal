package org.example.agent.engine;

import org.example.agent.core.ContentBlock;

public record ReminderMessage(String text) {
    public ContentBlock.Text toBlock() {
        return new ContentBlock.Text(text);
    }
}
