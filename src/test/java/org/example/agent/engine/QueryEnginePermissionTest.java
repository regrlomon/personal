package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.*;
import org.example.agent.permission.*;
import org.example.agent.tool.*;
import org.example.agent.core.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class QueryEnginePermissionTest {

    private static ModelClient fakeModel(String toolName, Map<String, Object> toolInput) {
        int[] callCount = {0};
        return request -> {
            callCount[0]++;
            if (callCount[0] == 1) {
                return new ModelResponse(
                        List.of(new ContentBlock.ToolUse("tid-1", toolName, toolInput)),
                        StopReason.TOOL_USE, 0, 0);
            }
            return new ModelResponse(List.of(new ContentBlock.Text("done")), StopReason.END_TURN, 0, 0);
        };
    }

    private static ToolRegistry registryWithDummyBash() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("bash", "runs bash", Map.of(
                        "type", "object",
                        "properties", Map.of("command", Map.of("type", "string")),
                        "required", List.of("command")));
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("bash ran: " + input.get("command"));
            }
        });
        return registry;
    }

    @Test
    void plan_mode_causes_tool_result_to_contain_permission_denied() {
        var checker = new PermissionChecker(PermissionMode.PLAN);
        var registry = registryWithDummyBash();
        var engine = new QueryEngine(
                fakeModel("bash", Map.of("command", "echo hello")),
                registry,
                checker,
                UserConfirmation.alwaysDeny()
        );
        var params = new QueryParams(
                List.of(Message.user("run bash")),
                null, null, null, 5
        );
        var result = (QueryResult.Success) engine.run(params);
        var toolResultContent = result.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.ToolResult)
                .map(b -> ((ContentBlock.ToolResult) b).content())
                .findFirst()
                .orElse("");
        assertTrue(toolResultContent.contains("Permission denied"),
                "Expected Permission denied in tool result but got: " + toolResultContent);
    }

    @Test
    void auto_mode_allows_read_file_without_user_prompt() {
        var checker = new PermissionChecker(PermissionMode.AUTO);
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("read_file", "reads file", Map.of(
                        "type", "object",
                        "properties", Map.of("path", Map.of("type", "string")),
                        "required", List.of("path")));
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("file content");
            }
        });
        var engine = new QueryEngine(
                fakeModel("read_file", Map.of("path", "README.md")),
                registry,
                checker,
                UserConfirmation.alwaysDeny()
        );
        var params = new QueryParams(
                List.of(Message.user("read readme")),
                null, null, null, 5
        );
        var result = (QueryResult.Success) engine.run(params);
        var toolResultContent = result.messages().stream()
                .filter(m -> m.role() == Role.USER)
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.ToolResult)
                .map(b -> ((ContentBlock.ToolResult) b).content())
                .findFirst()
                .orElse("");
        assertEquals("file content", toolResultContent);
    }
}
