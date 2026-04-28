package org.example.agent.tool.background;

import java.util.Objects;

public record BackgroundNotification(
        String taskId,
        String description,
        RuntimeTaskStatus status,
        String preview
) {
    public BackgroundNotification {
        Objects.requireNonNull(taskId,      "taskId must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(status,      "status must not be null");
        Objects.requireNonNull(preview,     "preview must not be null");
    }
}
