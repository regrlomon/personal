package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;
import org.example.agent.hook.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionRuntimeHookTest {

    private static final ToolUseContext BASE_CTX = ToolUseContext.defaults(".");

    private static Tool echoTool(String name, String output) {
        return new Tool() {
            @Override public ToolDefinition definition() {
                return new ToolDefinition(name, "", Map.of());
            }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success(output);
            }
        };
    }

    private static ToolExecutionRuntime runtimeWithTool(Tool tool) {
        var registry = new ToolRegistry();
        registry.register(tool);
        return new ToolExecutionRuntime(new ToolRouter(registry), ForkJoinPool.commonPool());
    }

    @Test
    void pre_tool_exit_1_blocks_execution_and_returns_error() {
        HookRunner blockAll = event -> HookResult.block("blocked by policy");
        var ctx = BASE_CTX.withHookRunner(blockAll);
        var runtime = runtimeWithTool(echoTool("bash", "should not run"));

        var result = runtime.execute(
                List.of(new ContentBlock.ToolUse("id1", "bash", Map.of())), ctx);

        assertEquals(1, result.toolResults().size());
        assertTrue(result.toolResults().get(0).content().contains("blocked by policy"),
                "Expected block message in result, got: " + result.toolResults().get(0).content());
        assertTrue(result.injectionMessages().isEmpty());
    }

    @Test
    void pre_tool_exit_2_runs_tool_and_adds_injection_message() {
        HookRunner injectPre = event -> {
            if (event.name() == HookEventName.PRE_TOOL_USE) return HookResult.inject("pre-context");
            return HookResult.ok();
        };
        var ctx = BASE_CTX.withHookRunner(injectPre);
        var runtime = runtimeWithTool(echoTool("bash", "tool output"));

        var result = runtime.execute(
                List.of(new ContentBlock.ToolUse("id1", "bash", Map.of())), ctx);

        assertEquals("tool output", result.toolResults().get(0).content());
        assertTrue(result.injectionMessages().contains("pre-context"),
                "Expected pre-context in injections, got: " + result.injectionMessages());
    }

    @Test
    void post_tool_exit_2_adds_injection_message_after_tool_runs() {
        HookRunner injectPost = event -> {
            if (event.name() == HookEventName.POST_TOOL_USE) return HookResult.inject("audit: tool ran");
            return HookResult.ok();
        };
        var ctx = BASE_CTX.withHookRunner(injectPost);
        var runtime = runtimeWithTool(echoTool("bash", "tool output"));

        var result = runtime.execute(
                List.of(new ContentBlock.ToolUse("id1", "bash", Map.of())), ctx);

        assertEquals("tool output", result.toolResults().get(0).content());
        assertTrue(result.injectionMessages().contains("audit: tool ran"),
                "Expected audit message in injections, got: " + result.injectionMessages());
    }

    @Test
    void no_hook_runner_produces_empty_injection_messages() {
        var runtime = runtimeWithTool(echoTool("bash", "output"));

        var result = runtime.execute(
                List.of(new ContentBlock.ToolUse("id1", "bash", Map.of())), BASE_CTX);

        assertTrue(result.injectionMessages().isEmpty());
    }

    @Test
    void both_pre_and_post_injections_collected() {
        HookRunner both = event -> switch (event.name()) {
            case PRE_TOOL_USE  -> HookResult.inject("pre-msg");
            case POST_TOOL_USE -> HookResult.inject("post-msg");
            default            -> HookResult.ok();
        };
        var ctx = BASE_CTX.withHookRunner(both);
        var runtime = runtimeWithTool(echoTool("bash", "done"));

        var result = runtime.execute(
                List.of(new ContentBlock.ToolUse("id1", "bash", Map.of())), ctx);

        assertEquals(List.of("pre-msg", "post-msg"), result.injectionMessages());
    }
}
