package org.example.agent.core;

import java.util.List;

public record QueryParams(
        List<Message> messages,
        String systemPrompt,
        String fallbackModel,           // nullable
        Integer maxOutputTokensOverride, // nullable
        Integer maxTurns                 // nullable
) {}
