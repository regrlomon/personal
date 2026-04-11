package org.example.agent.core;

import java.util.List;

public sealed interface TransitionReason permits
        TransitionReason.ToolResultContinuation,
        TransitionReason.MaxTokensRecovery,
        TransitionReason.CompactRetry,
        TransitionReason.TransportRetry,
        TransitionReason.StopHookContinuation,
        TransitionReason.BudgetContinuation {

    record ToolResultContinuation(List<ContentBlock.ToolResult> results) implements TransitionReason {}

    record MaxTokensRecovery(int attempt) implements TransitionReason {}

    record CompactRetry() implements TransitionReason {}

    record TransportRetry(int attempt, Throwable cause) implements TransitionReason {}

    record StopHookContinuation(String hookName) implements TransitionReason {}

    record BudgetContinuation() implements TransitionReason {}
}
