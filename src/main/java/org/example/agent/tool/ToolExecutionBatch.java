package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import java.util.List;

public record ToolExecutionBatch(
        List<ContentBlock.ToolUse> toolUses,
        boolean concurrencySafe
) {}
