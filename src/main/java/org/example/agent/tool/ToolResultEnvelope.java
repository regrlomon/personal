package org.example.agent.tool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

public record ToolResultEnvelope(
        boolean ok,
        String content,
        boolean isError,
        List<Object> attachments,
        Optional<UnaryOperator<ToolUseContext>> contextModifier
) {
    public ToolResultEnvelope {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(contextModifier, "contextModifier must not be null");
    }

    public static ToolResultEnvelope success(String content) {
        return new ToolResultEnvelope(true, content, false, List.of(), Optional.empty());
    }

    public static ToolResultEnvelope error(String message) {
        return new ToolResultEnvelope(false, message, true, List.of(), Optional.empty());
    }
}
