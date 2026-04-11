package org.example.agent.model;

import org.example.agent.core.Message;
import org.example.agent.core.ToolDefinition;

import java.util.List;

public record ModelRequest(
        List<Message> messages,
        String systemPrompt,
        List<ToolDefinition> tools,
        Integer maxOutputTokens  // nullable
) {}
