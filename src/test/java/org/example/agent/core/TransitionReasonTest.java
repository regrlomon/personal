package org.example.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransitionReasonTest {

    @Test
    void tool_result_continuation_holds_results() {
        var result = new ContentBlock.ToolResult("id-1", "output");
        TransitionReason t = new TransitionReason.ToolResultContinuation(List.of(result));
        assertInstanceOf(TransitionReason.ToolResultContinuation.class, t);
        assertEquals(1, ((TransitionReason.ToolResultContinuation) t).results().size());
    }

    @Test
    void max_tokens_recovery_holds_attempt_count() {
        TransitionReason t = new TransitionReason.MaxTokensRecovery(2);
        assertEquals(2, ((TransitionReason.MaxTokensRecovery) t).attempt());
    }

    @Test
    void pattern_matching_switch_is_exhaustive() {
        // If TransitionReason gains a new subtype without a case here, this won't compile.
        TransitionReason t = new TransitionReason.CompactRetry();
        String label = switch (t) {
            case TransitionReason.ToolResultContinuation c -> "tool";
            case TransitionReason.MaxTokensRecovery m      -> "max_tokens";
            case TransitionReason.CompactRetry c           -> "compact";
            case TransitionReason.TransportRetry r         -> "transport";
            case TransitionReason.StopHookContinuation h   -> "stop_hook";
            case TransitionReason.BudgetContinuation b     -> "budget";
        };
        assertEquals("compact", label);
    }

    @Test
    void transport_retry_holds_attempt_and_cause() {
        var cause = new RuntimeException("timeout");
        TransitionReason t = new TransitionReason.TransportRetry(1, cause);
        var retry = (TransitionReason.TransportRetry) t;
        assertEquals(1, retry.attempt());
        assertSame(cause, retry.cause());
    }
}
