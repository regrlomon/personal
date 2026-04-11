package org.example.agent.model;

import org.example.agent.core.ContentBlock;

import java.util.List;

public record ModelResponse(
        List<ContentBlock> content,
        StopReason stopReason,
        int inputTokens,
        int outputTokens
) {}
