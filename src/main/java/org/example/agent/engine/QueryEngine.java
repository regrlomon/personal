package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelClient;
import org.example.agent.model.ModelRequest;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.hook.HookEvent;
import org.example.agent.hook.HookEventName;
import org.example.agent.hook.HookRunner;
import org.example.agent.permission.PermissionChecker;
import org.example.agent.permission.UserConfirmation;
import org.example.agent.tool.*;
import org.example.agent.tool.skill.SkillRegistry;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;

public class QueryEngine {

    private static final String CONTINUE_PROMPT = "Please continue.";
    private static final String REMINDER_TEXT =
            "<reminder>Refresh your todo plan before continuing.</reminder>";

    private static final int TRANSPORT_RETRY_BUDGET = 3; // max retry attempts (4 total calls)

    private static final List<String> TRANSIENT_KEYWORDS =
            List.of("timeout", "rate", "unavailable", "connection");

    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final ToolExecutionRuntime runtime;
    private final SkillRegistry skillRegistry;
    private final ContextCompactor compactor;
    private final PermissionChecker permissionChecker;
    private final UserConfirmation   userConfirmation;
    private final HookRunner hookRunner;
    private final MemoryStore memoryStore;
    private final SystemPromptBuilder promptBuilder;
    private final MessagePipeline messagePipeline;
    private final long backoffUnitMs;

    // Promoted to instance fields so CompactTool lambdas (wired in Task 6) can read live values.
    // Only valid during an active run() call.
    private QueryState     currentState;
    private ToolUseContext currentCtx;

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry) {
        this(modelClient, toolRegistry, null, defaultCompactor(), ForkJoinPool.commonPool(), null, null, null, null, 1000L);
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, ExecutorService executor) {
        this(modelClient, toolRegistry, null, defaultCompactor(), executor, null, null, null, null, 1000L);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                       SkillRegistry skillRegistry) {
        this(modelClient, toolRegistry, skillRegistry, defaultCompactor(), ForkJoinPool.commonPool(), null, null, null, null, 1000L);
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                ContextCompactor compactor, ExecutorService executor) {
        this(modelClient, toolRegistry, null, compactor, executor, null, null, null, null, 1000L);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                       PermissionChecker permissionChecker, UserConfirmation userConfirmation) {
        this(modelClient, toolRegistry, null, defaultCompactor(),
                ForkJoinPool.commonPool(), permissionChecker, userConfirmation, null, null, 1000L);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, HookRunner hookRunner) {
        this(modelClient, toolRegistry, null, defaultCompactor(),
                ForkJoinPool.commonPool(), null, null, hookRunner, null, 1000L);
    }

    public QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, MemoryStore memoryStore) {
        this(modelClient, toolRegistry, null, defaultCompactor(),
                ForkJoinPool.commonPool(), null, null, null, memoryStore, 1000L);
    }

    QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry, long backoffUnitMs) {
        this(modelClient, toolRegistry, null, defaultCompactor(),
                ForkJoinPool.commonPool(), null, null, null, null, backoffUnitMs);
    }

    private QueryEngine(ModelClient modelClient, ToolRegistry toolRegistry,
                        SkillRegistry skillRegistry, ContextCompactor compactor,
                        ExecutorService executor,
                        PermissionChecker permissionChecker, UserConfirmation userConfirmation,
                        HookRunner hookRunner, MemoryStore memoryStore,
                        long backoffUnitMs) {
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.skillRegistry = skillRegistry;
        this.compactor = compactor;
        this.permissionChecker = permissionChecker;
        this.userConfirmation = userConfirmation;
        this.hookRunner = hookRunner;
        this.memoryStore = memoryStore;
        this.backoffUnitMs = backoffUnitMs;
        var router = new ToolRouter(toolRegistry);
        this.runtime = new ToolExecutionRuntime(router, executor);
        toolRegistry.register(new CompactTool(
                compactor,
                () -> List.copyOf(currentState.messages()),
                msgs -> currentState.replaceMessages(msgs),
                () -> currentCtx.planningState()
        ));
        this.promptBuilder = new SystemPromptBuilder(skillRegistry, memoryStore,
                System.getProperty("user.dir"));
        this.messagePipeline = new MessagePipeline();
    }

    private static ContextCompactor defaultCompactor() {
        var dir = Paths.get(System.getProperty("user.dir"),
                ".task_outputs", "tool-results");
        return new ContextCompactor(dir);
    }

    public QueryResult run(QueryParams params) {
        currentState = QueryState.from(params);
        var baseCtx = ToolUseContext.defaults(System.getProperty("user.dir"));
        var ctx = baseCtx;
        if (permissionChecker != null) ctx = ctx.withPermissions(permissionChecker, userConfirmation);
        if (hookRunner != null) ctx = ctx.withHookRunner(hookRunner);
        currentCtx = ctx;

        // SessionStart hook
        if (hookRunner != null) {
            var sysPrompt = params.systemPrompt() != null ? params.systemPrompt() : "";
            hookRunner.run(new HookEvent(HookEventName.SESSION_START,
                    Map.of("system_prompt", sysPrompt)));
        }

        while (true) {
            // Layer 2: trim old tool results before every model call
            currentState.replaceMessages(compactor.microCompact(currentState.messages()));

            if (params.maxTurns() != null && currentState.turnCount() > params.maxTurns()) {
                return new QueryResult.Success(currentState.messages(), currentState.turnCount());
            }

            ModelResponse response;
            try {
                response = modelClient.call(buildRequest(currentState, params));
            } catch (Exception e) {
                if (currentState.transportRetryCount() >= TRANSPORT_RETRY_BUDGET) {
                    throw e;
                }
                var transition = classifyException(currentState.transportRetryCount() + 1, e);
                if (transition == null) throw e;
                advance(currentState, transition, null);
                continue;
            }

            if (response.stopReason() == StopReason.TOOL_USE) {
                var toolUses = response.content().stream()
                        .filter(b -> b instanceof ContentBlock.ToolUse)
                        .map(b -> (ContentBlock.ToolUse) b)
                        .toList();
                if (toolUses.isEmpty()) {
                    currentState.appendMessage(new Message(Role.ASSISTANT, response.content()));
                    return new QueryResult.Success(currentState.messages(), currentState.turnCount());
                }
                currentCtx.planningState().tickRound();
                var execResult = runtime.execute(toolUses, currentCtx);
                currentCtx = execResult.updatedContext();
                currentState.appendMessage(new Message(Role.ASSISTANT, response.content()));
                currentState.appendMessage(buildToolResultMessage(execResult.toolResults()));
                for (String msg : execResult.injectionMessages()) {
                    currentState.appendMessage(Message.user(msg));
                }
                currentState.setLastTransition(
                        new TransitionReason.ToolResultContinuation(execResult.toolResults()));
                currentState.incrementTurn();
            } else {
                var transition = decide(currentState, response);
                if (transition == null) {
                    currentState.appendMessage(new Message(Role.ASSISTANT, response.content()));
                    return new QueryResult.Success(currentState.messages(), currentState.turnCount());
                }
                advance(currentState, transition, response);
            }
        }
    }

    private TransitionReason decide(QueryState state, ModelResponse response) {
        return switch (response.stopReason()) {
            case END_TURN -> null;
            case TOOL_USE -> throw new IllegalStateException("TOOL_USE handled in run()");
            case MAX_TOKENS -> state.hasAttemptedCompact()
                    ? new TransitionReason.MaxTokensRecovery(state.continuationCount() + 1)
                    : new TransitionReason.CompactRetry();
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
            case TransitionReason.CompactRetry c -> {
                state.appendMessage(new Message(Role.ASSISTANT, response.content()));
                var compacted = compactor.fullCompact(state.messages(), currentCtx.planningState());
                state.replaceMessages(compacted);
                state.markCompactAttempted();
                state.setLastTransition(c);
                // intentionally no incrementTurn — retry immediately
            }
            case TransitionReason.TransportRetry r -> {
                System.out.printf("[Recovery] backoff attempt=%d cause=%s%n",
                        r.attempt(), r.cause().getMessage());
                try {
                    Thread.sleep((long) backoffDelay(r.attempt()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
                state.incrementTransportRetry();
                state.setLastTransition(r);
            }
            case TransitionReason.StopHookContinuation h -> { /* s08 extension */ }
            case TransitionReason.BudgetContinuation b   -> { /* budget extension */ }
            case TransitionReason.ToolResultContinuation c ->
                    throw new IllegalStateException("ToolResultContinuation should not reach advance()");
        }
    }

    private ModelRequest buildRequest(QueryState state, QueryParams params) {
        var reminders   = collectReminders();
        var attachments = collectAttachments();
        Integer maxTokens = state.maxOutputTokensOverride()
                .orElse(params.maxOutputTokensOverride());
        return new ModelRequest(
                messagePipeline.build(state.messages(), attachments, reminders),
                augmentSystemPrompt(params.systemPrompt()),
                toolRegistry.definitions(),
                maxTokens
        );
    }

    private static TransitionReason classifyException(int attempt, Throwable e) {
        var msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        if (TRANSIENT_KEYWORDS.stream().anyMatch(msg::contains)) {
            return new TransitionReason.TransportRetry(attempt, e);
        }
        return null;
    }

    private double backoffDelay(int attempt) {
        return Math.min((double) backoffUnitMs * Math.pow(2, attempt - 1), 30_000.0);
    }

    private List<ReminderMessage> collectReminders() {
        if (currentCtx != null && currentCtx.planningState().needsReminder()) {
            return List.of(new ReminderMessage(REMINDER_TEXT));
        }
        return List.of();
    }

    private List<Attachment> collectAttachments() {
        // Placeholder: large hook outputs and other attachments wired in a later task (s19).
        return List.of();
    }

    private String augmentSystemPrompt(String base) {
        return promptBuilder.build(base);
    }

    private Message buildToolResultMessage(List<ContentBlock.ToolResult> results) {
        List<ContentBlock> blocks = new ArrayList<>();
        // Layer 1: persist large tool outputs to disk
        for (var r : results) {
            var content = compactor.persistIfLarge(r.toolUseId(), r.content());
            blocks.add(new ContentBlock.ToolResult(r.toolUseId(), content));
        }
        return new Message(Role.USER, List.copyOf(blocks));
    }
}
