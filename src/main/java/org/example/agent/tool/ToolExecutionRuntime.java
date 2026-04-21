package org.example.agent.tool;

import org.example.agent.core.ContentBlock;

import java.util.ArrayList;
import java.util.List;
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
        var currentCtx = ctx;

        for (var batch : batches) {
            var envelopes = executeBatch(batch, currentCtx);
            for (int i = 0; i < envelopes.size(); i++) {
                var envelope = envelopes.get(i);
                var toolUse = batch.toolUses().get(i);
                allResults.add(new ContentBlock.ToolResult(toolUse.id(), envelope.content()));
                if (envelope.contextModifier().isPresent()) {
                    currentCtx = envelope.contextModifier().get().apply(currentCtx);
                }
            }
        }
        return new ExecutionResult(List.copyOf(allResults), currentCtx);
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

    private List<ToolResultEnvelope> executeBatch(ToolExecutionBatch batch, ToolUseContext ctx) {
        if (batch.concurrencySafe()) {
            return executeConcurrently(batch.toolUses(), ctx);
        }
        return executeSerially(batch.toolUses(), ctx);
    }

    private List<ToolResultEnvelope> executeConcurrently(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        var futures = toolUses.stream()
                .map(tu -> CompletableFuture.supplyAsync(() -> routeSafely(tu, ctx), executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return futures.stream().map(CompletableFuture::join).toList();
    }

    private List<ToolResultEnvelope> executeSerially(List<ContentBlock.ToolUse> toolUses, ToolUseContext ctx) {
        return toolUses.stream().map(tu -> routeSafely(tu, ctx)).toList();
    }

    private ToolResultEnvelope routeSafely(ContentBlock.ToolUse toolUse, ToolUseContext ctx) {
        try {
            return router.routeToEnvelope(toolUse, ctx);
        } catch (UnknownToolException e) {
            return ToolResultEnvelope.error(e.getMessage());
        } catch (UnsupportedOperationException e) {
            return ToolResultEnvelope.error("MCP tools not yet supported");
        }
    }
}
