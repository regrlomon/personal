package org.example.agent.tool;

import java.util.List;

public record ToolResultEnvelope(
        boolean ok,
        String content,
        boolean isError,
        List<Object> attachments
) {
    public static ToolResultEnvelope success(String content) {
        return new ToolResultEnvelope(true, content, false, List.of());
    }

    public static ToolResultEnvelope error(String message) {
        return new ToolResultEnvelope(false, message, true, List.of());
    }
}
