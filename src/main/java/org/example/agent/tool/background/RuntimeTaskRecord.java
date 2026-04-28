package org.example.agent.tool.background;

import java.nio.file.Path;
import java.util.Objects;

public record RuntimeTaskRecord(
        String id,
        String description,
        RuntimeTaskStatus status,
        long startedAt,
        String resultPreview,
        Path outputFile
) {
    public RuntimeTaskRecord {
        Objects.requireNonNull(id,            "id must not be null");
        Objects.requireNonNull(description,   "description must not be null");
        Objects.requireNonNull(status,        "status must not be null");
        Objects.requireNonNull(resultPreview, "resultPreview must not be null");
        Objects.requireNonNull(outputFile,    "outputFile must not be null");
    }
}
