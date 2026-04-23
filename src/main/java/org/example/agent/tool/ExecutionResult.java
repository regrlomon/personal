package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import java.util.List;

public record ExecutionResult(
        List<ContentBlock.ToolResult> toolResults,
        ToolUseContext updatedContext,
        List<String> injectionMessages
) {}
