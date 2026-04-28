package org.example.agent.tool.task;

import java.util.List;
import java.util.Objects;

public record TaskRecord(
        int id,
        String subject,
        String description,
        TaskStatus status,
        List<Integer> blockedBy,
        List<Integer> blocks,
        String owner
) {
    public TaskRecord {
        Objects.requireNonNull(subject,     "subject must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(status,      "status must not be null");
        Objects.requireNonNull(blockedBy,   "blockedBy must not be null");
        Objects.requireNonNull(blocks,      "blocks must not be null");
        Objects.requireNonNull(owner,       "owner must not be null");
        blockedBy = List.copyOf(blockedBy);
        blocks    = List.copyOf(blocks);
    }
}
