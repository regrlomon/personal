package org.example.agent.core;

public record MemoryEntry(
        String name,
        String description,
        String type,
        String content
) {}
