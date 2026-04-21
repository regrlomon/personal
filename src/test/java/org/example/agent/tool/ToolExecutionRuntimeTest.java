package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class ToolExecutionRuntimeTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ToolUseContext ctx = ToolUseContext.defaults(".");

    @AfterEach
    void tearDown() { executor.shutdownNow(); }

    private Tool simpleTool(String name, String output, boolean safe) {
        return new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition(name, "", Map.of()); }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success(output);
            }
            @Override public boolean isConcurrencySafe() { return safe; }
        };
    }

    private ToolExecutionRuntime runtime(Tool... tools) {
        var registry = new ToolRegistry();
        for (var t : tools) registry.register(t);
        return new ToolExecutionRuntime(new ToolRouter(registry), executor);
    }

    @Test
    void mixed_safe_and_unsafe_tools_execute_in_original_order() {
        // r1,r2 safe → concurrent batch; w1 unsafe → exclusive; r3 safe → concurrent
        // all should appear in original order in results
        var rt = runtime(
            simpleTool("r1", "a", true), simpleTool("r2", "b", true),
            simpleTool("w1", "c", false), simpleTool("r3", "d", true)
        );
        var toolUses = List.of(
            new ContentBlock.ToolUse("1", "r1", Map.of()),
            new ContentBlock.ToolUse("2", "r2", Map.of()),
            new ContentBlock.ToolUse("3", "w1", Map.of()),
            new ContentBlock.ToolUse("4", "r3", Map.of())
        );

        var result = rt.execute(toolUses, ctx);

        assertEquals(4, result.toolResults().size());
        assertEquals("1", result.toolResults().get(0).toolUseId());
        assertEquals("a", result.toolResults().get(0).content());
        assertEquals("2", result.toolResults().get(1).toolUseId());
        assertEquals("b", result.toolResults().get(1).content());
        assertEquals("3", result.toolResults().get(2).toolUseId());
        assertEquals("c", result.toolResults().get(2).content());
        assertEquals("4", result.toolResults().get(3).toolUseId());
        assertEquals("d", result.toolResults().get(3).content());
    }

    @Test
    void results_returned_in_original_order() {
        var rt = runtime(simpleTool("t1", "first", true), simpleTool("t2", "second", true));
        var toolUses = List.of(
            new ContentBlock.ToolUse("id1", "t1", Map.of()),
            new ContentBlock.ToolUse("id2", "t2", Map.of())
        );

        var result = rt.execute(toolUses, ctx);

        assertEquals(2, result.toolResults().size());
        assertEquals("id1", result.toolResults().get(0).toolUseId());
        assertEquals("first", result.toolResults().get(0).content());
        assertEquals("id2", result.toolResults().get(1).toolUseId());
        assertEquals("second", result.toolResults().get(1).content());
    }

    @Test
    void safe_batch_executes_tools_concurrently() {
        var latch = new CountDownLatch(2);
        var bothStarted = new AtomicBoolean(false);

        Tool blocking1 = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("b1", "", Map.of()); }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                latch.countDown();
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ToolResultEnvelope.success("b1");
            }
            @Override public boolean isConcurrencySafe() { return true; }
        };
        Tool blocking2 = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("b2", "", Map.of()); }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                latch.countDown();
                try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                bothStarted.set(true);
                return ToolResultEnvelope.success("b2");
            }
            @Override public boolean isConcurrencySafe() { return true; }
        };

        var registry = new ToolRegistry();
        registry.register(blocking1);
        registry.register(blocking2);
        var rt = new ToolExecutionRuntime(new ToolRouter(registry), executor);

        var result = rt.execute(List.of(
            new ContentBlock.ToolUse("1", "b1", Map.of()),
            new ContentBlock.ToolUse("2", "b2", Map.of())
        ), ctx);

        assertTrue(bothStarted.get(), "both tools must start before either completes");
        assertEquals(2, result.toolResults().size());
    }

    @Test
    void context_modifiers_applied_in_original_order() {
        Tool toolA = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("tool_a", "", Map.of()); }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                UnaryOperator<ToolUseContext> mod = c -> {
                    var n = new ArrayList<>(c.notifications());
                    n.add("A");
                    return c.withNotifications(n);
                };
                return new ToolResultEnvelope(true, "a", false, List.of(), Optional.of(mod));
            }
            @Override public boolean isConcurrencySafe() { return true; }  // 走并发路径
        };
        Tool toolB = new Tool() {
            @Override public ToolDefinition definition() { return new ToolDefinition("tool_b", "", Map.of()); }
            @Override public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                UnaryOperator<ToolUseContext> mod = c -> {
                    var n = new ArrayList<>(c.notifications());
                    n.add("B");
                    return c.withNotifications(n);
                };
                return new ToolResultEnvelope(true, "b", false, List.of(), Optional.of(mod));
            }
            @Override public boolean isConcurrencySafe() { return true; }  // 走并发路径
        };

        var registry = new ToolRegistry();
        registry.register(toolA);
        registry.register(toolB);
        var rt = new ToolExecutionRuntime(new ToolRouter(registry), executor);

        var result = rt.execute(List.of(
            new ContentBlock.ToolUse("1", "tool_a", Map.of()),
            new ContentBlock.ToolUse("2", "tool_b", Map.of())
        ), ctx);

        assertEquals(List.of("A", "B"), result.updatedContext().notifications());
    }

    @Test
    void unknown_tool_returns_error_result() {
        var rt = runtime();
        var result = rt.execute(List.of(new ContentBlock.ToolUse("x", "nonexistent", Map.of())), ctx);
        assertEquals(1, result.toolResults().size());
        assertTrue(result.toolResults().get(0).content().contains("nonexistent"));
    }

    @Test
    void mcp_tool_returns_error_result() {
        var rt = runtime();
        var result = rt.execute(List.of(new ContentBlock.ToolUse("y", "mcp__db__query", Map.of())), ctx);
        assertEquals(1, result.toolResults().size());
        assertEquals("MCP tools not yet supported", result.toolResults().get(0).content());
    }
}
