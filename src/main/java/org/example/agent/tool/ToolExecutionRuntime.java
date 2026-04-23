package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.hook.HookEvent;
import org.example.agent.hook.HookEventName;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class ToolExecutionRuntime {

    private final ToolRouter router;
    private final ExecutorService executor;

    public ToolExecutionRuntime(ToolRouter router, ExecutorService executor) {
        this.router = Objects.requireNonNull(router, "router must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    public ExecutionResult execute(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        Objects.requireNonNull(toolUses, "toolUses must not be null");
        var batches = partition(toolUses);
        var allResults = new ArrayList<ContentBlock.ToolResult>();
        var allInjections = new ArrayList<String>();
        var currentCtx = ctx;

        for (var batch : batches) {
            var routeResults = executeBatch(batch, currentCtx);
            for (int i = 0; i < routeResults.size(); i++) {
                var rr = routeResults.get(i);
                var toolUse = batch.toolUses().get(i);
                allResults.add(new ContentBlock.ToolResult(toolUse.id(), rr.envelope().content()));
                allInjections.addAll(rr.injectionMessages());
                if (rr.envelope().contextModifier().isPresent()) {
                    currentCtx = rr.envelope().contextModifier().get().apply(currentCtx);
                }
            }
        }
        return new ExecutionResult(List.copyOf(allResults), currentCtx, List.copyOf(allInjections));
    }

    private List<ToolExecutionBatch> partition(List<ContentBlock.ToolUse> toolUses) {
        var batches = new ArrayList<ToolExecutionBatch>();
        var safeBatch = new ArrayList<ContentBlock.ToolUse>();

        for (var toolUse : toolUses) {
            if (router.isConcurrencySafe(toolUse.name())) {
                safeBatch.add(toolUse);
            } else {
                if (!safeBatch.isEmpty()) {
                    batches.add(new ToolExecutionBatch(List.copyOf(safeBatch), true));
                    safeBatch.clear();
                }
                batches.add(new ToolExecutionBatch(List.of(toolUse), false));
            }
        }
        if (!safeBatch.isEmpty()) {
            batches.add(new ToolExecutionBatch(List.copyOf(safeBatch), true));
        }
        return batches;
    }

    private List<RouteResult> executeBatch(ToolExecutionBatch batch, ToolUseContext ctx) {
        if (batch.concurrencySafe()) return executeConcurrently(batch.toolUses(), ctx);
        return executeSerially(batch.toolUses(), ctx);
    }

    private List<RouteResult> executeConcurrently(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        var futures = toolUses.stream()
                .map(tu -> CompletableFuture.supplyAsync(() -> routeSafely(tu, ctx), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private List<RouteResult> executeSerially(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        return toolUses.stream().map(tu -> routeSafely(tu, ctx)).toList();
    }

    private RouteResult routeSafely(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        var hookRunner = ctx.hookRunner();
        var injections = new ArrayList<String>();

        // PreToolUse hook
        if (hookRunner != null) {
            var pre = hookRunner.run(new HookEvent(HookEventName.PRE_TOOL_USE,
                    Map.of("tool_name", toolUse.name(), "input", toolUse.input())));
            if (pre.exitCode() == 1) {
                return new RouteResult(ToolResultEnvelope.error(pre.message()), List.of());
            }
            if (pre.exitCode() == 2) injections.add(pre.message());
        }

        // Execute tool
        ToolResultEnvelope envelope;
        try {
            envelope = router.routeToEnvelope(toolUse, ctx);
        } catch (UnknownToolException e) {
            envelope = ToolResultEnvelope.error(e.getMessage());
        } catch (UnsupportedOperationException e) {
            envelope = ToolResultEnvelope.error("MCP tools not yet supported");
        }

        // PostToolUse hook
        if (hookRunner != null) {
            var post = hookRunner.run(new HookEvent(HookEventName.POST_TOOL_USE,
                    Map.copyOf(Map.of("tool_name", toolUse.name(), "input", toolUse.input(),
                            "output", envelope.content()))));
            if (post.exitCode() == 2) injections.add(post.message());
        }

        return new RouteResult(envelope, List.copyOf(injections));
    }

    private record RouteResult(ToolResultEnvelope envelope, List<String> injectionMessages) {}
}
