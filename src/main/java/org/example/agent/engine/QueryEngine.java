package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelClient;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.tool.ToolRegistry;

import java.util.List;

public class QueryEngine {

    private static final String CONTINUE_PROMPT = "Please continue.";

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
    }

    public QueryResult run(QueryParams params) {
        var state = QueryState.from(params);
        while (true) {
            var request = buildRequest(state, params);
            var response = modelClient.call(request);

            var transition = decide(state, response);
            if (transition == null) {
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                return new QueryResult.Success(state.messages(), state.turnCount());
            }
            advance(state, transition, response);
        }
    }

    // decide: reads response, returns transition reason or null (terminal)
    // Never mutates state.
    private TransitionReason decide(QueryState state, ModelResponse response) {
        return switch (response.stopReason()) {
            case END_TURN -> null;
            case TOOL_USE -> new TransitionReason.ToolResultContinuation(collectResults(response));
            case MAX_TOKENS -> new TransitionReason.MaxTokensRecovery(state.continuationCount() + 1);
            case STOP_SEQUENCE -> null;
        };
    }

    // advance: mutates state based on transition reason.
    // Never calls the model. Compiler enforces exhaustive coverage.
    private void advance(QueryState state, TransitionReason t, ModelResponse response) {
        switch (t) {
            case TransitionReason.ToolResultContinuation c -> {
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                state.appendMessage(buildToolResultMessage(c.results()));
                state.setLastTransition(t);
                state.incrementTurn();
            }
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
        }
    }

    private ModelRequest buildRequest(QueryState state, QueryParams params) {
        Integer maxTokens = state.maxOutputTokensOverride()
                .orElse(params.maxOutputTokensOverride());
        return new ModelRequest(
                state.messages(),
                params.systemPrompt(),
                toolRegistry.definitions(),
                maxTokens
        );
    }

    private List<ContentBlock.ToolResult> collectResults(ModelResponse response) {
        return response.content().stream()
                .filter(b -> b instanceof ContentBlock.ToolUse)
                .map(b -> toolRegistry.execute((ContentBlock.ToolUse) b))
                .toList();
    }

    private Message buildToolResultMessage(List<ContentBlock.ToolResult> results) {
        return new Message(Role.USER, List.copyOf(results));
    }
}
