package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelClient;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

public class QueryEngine {

    private static final String CONTINUE_PROMPT = "Please continue.";
    private static final String REMINDER_TEXT =
            "<reminder>Refresh your todo plan before continuing.</reminder>";

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionRuntime runtime;

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this(modelClient, toolRegistry, ForkJoinPool.commonPool());
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, ExecutorService executor) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        var router = new ToolRouter(toolRegistry);
        this.runtime = new ToolExecutionRuntime(router, executor);
    }

    public QueryResult run(QueryParams params) {
        var state = QueryState.from(params);
        var ctx = ToolUseContext.defaults(System.getProperty("user.dir"));
        while (true) {
            if (params.maxTurns() != null && state.turnCount() > params.maxTurns()) {
                return new QueryResult.Success(state.messages(), state.turnCount());
            }
            var response = modelClient.call(buildRequest(state, params));

            if (response.stopReason() == StopReason.TOOL_USE) {
                var toolUses = response.content().stream()
                        .filter(b -> b instanceof ContentBlock.ToolUse)
                        .map(b -> (ContentBlock.ToolUse) b)
                        .toList();
                if (toolUses.isEmpty()) {
                    state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                    return new QueryResult.Success(state.messages(), state.turnCount());
                }
                ctx.planningState().tickRound();
                var execResult = runtime.execute(toolUses, ctx);
                ctx = execResult.updatedContext();
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                state.appendMessage(buildToolResultMessage(execResult.toolResults(), ctx.planningState().needsReminder()));
                state.setLastTransition(new TransitionReason.ToolResultContinuation(execResult.toolResults()));
                state.incrementTurn();
            } else {
                var transition = decide(state, response);
                if (transition == null) {
                    state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                    return new QueryResult.Success(state.messages(), state.turnCount());
                }
                advance(state, transition, response);
            }
        }
    }

    private TransitionReason decide(QueryState state, ModelResponse response) {
        return switch (response.stopReason()) {
            case END_TURN -> null;
            case TOOL_USE -> throw new IllegalStateException("TOOL_USE handled in run()");
            case MAX_TOKENS -> new TransitionReason.MaxTokensRecovery(state.continuationCount() + 1);
            case STOP_SEQUENCE -> null;
        };
    }

    private void advance(QueryState state, TransitionReason t, ModelResponse response) {
        switch (t) {
            case TransitionReason.MaxTokensRecovery m -> {
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                state.appendMessage(Message.user(CONTINUE_PROMPT));
                state.incrementContinuation();
                state.setLastTransition(t);
                state.incrementTurn();
            }
            case TransitionReason.CompactRetry c -> { /* s06 extension */ }
            case TransitionReason.TransportRetry r -> { /* s11 extension */ }
            case TransitionReason.StopHookContinuation h -> { /* s08 extension */ }
            case TransitionReason.BudgetContinuation b -> { /* budget extension */ }
            case TransitionReason.ToolResultContinuation c -> throw new IllegalStateException("ToolResultContinuation should not reach advance()");
        }
    }

    private ModelRequest buildRequest(QueryState state, QueryParams params) {
        Integer maxTokens = state.maxOutputTokensOverride()
                .orElse(params.maxOutputTokensOverride());
        return new ModelRequest(
                MessageNormalizer.normalize(state.messages()),
                params.systemPrompt(),
                toolRegistry.definitions(),
                maxTokens
        );
    }

    private Message buildToolResultMessage(List<ContentBlock.ToolResult> results, boolean prependReminder) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (prependReminder) {
            blocks.add(new ContentBlock.Text(REMINDER_TEXT));
        }
        blocks.addAll(results);
        return new Message(Role.USER, List.copyOf(blocks));
    }
}
