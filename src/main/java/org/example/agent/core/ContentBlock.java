package org.example.agent.core;

import java.util.Map;

public sealed interface ContentBlock permits
        ContentBlock.Text,
        ContentBlock.ToolUse,
        ContentBlock.ToolResult {

    record Text(String text) implements ContentBlock {}

    record ToolUse(String id, String name, Map<String, Object> input) implements ContentBlock {}

    record ToolResult(String toolUseId, String content) implements ContentBlock {}
}
