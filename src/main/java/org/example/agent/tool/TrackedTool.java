package org.example.agent.tool;

public record TrackedTool(
        String id,
        String name,
        Status status,
        ToolResultEnvelope result
) {
    public enum Status { QUEUED, EXECUTING, COMPLETED }
}
