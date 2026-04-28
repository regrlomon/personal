package org.example.agent.tool.task;

import java.util.List;
import java.util.Objects;

public record TaskPatch(
        TaskStatus status,          // null = no change
        String subject,             // null = no change
        String description,         // null = no change
        String owner,               // null = no change
        List<Integer> addBlockedBy,
        List<Integer> addBlocks
) {
    public TaskPatch {
        Objects.requireNonNull(addBlockedBy, "addBlockedBy must not be null");
        Objects.requireNonNull(addBlocks,    "addBlocks must not be null");
        addBlockedBy = List.copyOf(addBlockedBy);
        addBlocks    = List.copyOf(addBlocks);
    }
}
