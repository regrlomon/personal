package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;
import java.util.Map;

public interface Tool {
    ToolDefinition definition();
    ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx);
}
