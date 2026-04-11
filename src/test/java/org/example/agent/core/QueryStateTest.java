package org.example.agent.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryStateTest {

    private QueryParams minimalParams() {
        return new QueryParams(
                List.of(Message.user("hello")),
                "You are helpful.",
                null, null, null
        );
    }

    @Test
    void from_params_initializes_defaults() {
        var state = QueryState.from(minimalParams());
        assertEquals(1, state.messages().size());
        assertEquals(1, state.turnCount());
        assertEquals(0, state.continuationCount());
        assertFalse(state.hasAttemptedCompact());
        assertFalse(state.stopHookActive());
        assertTrue(state.maxOutputTokensOverride().isEmpty());
        assertTrue(state.lastTransition().isEmpty());
    }

    @Test
    void from_params_copies_messages_not_reference() {
        var originalMessages = new java.util.ArrayList<>(List.of(Message.user("hello")));
        var params = new QueryParams(originalMessages, "system", null, null, null);
        var state = QueryState.from(params);

        // Mutate original list — state should be unaffected
        originalMessages.add(Message.user("injected"));
        assertEquals(1, state.messages().size());
    }

    @Test
    void append_message_adds_to_messages() {
        var state = QueryState.from(minimalParams());
        state.appendMessage(Message.assistant("hi"));
        assertEquals(2, state.messages().size());
        assertEquals(Role.ASSISTANT, state.messages().get(1).role());
    }

    @Test
    void messages_returns_unmodifiable_view() {
        var state = QueryState.from(minimalParams());
        assertThrows(UnsupportedOperationException.class,
                () -> state.messages().add(Message.user("hack")));
    }

    @Test
    void increment_turn_increments_count() {
        var state = QueryState.from(minimalParams());
        state.incrementTurn();
        state.incrementTurn();
        assertEquals(3, state.turnCount());
    }

    @Test
    void increment_continuation_increments_count() {
        var state = QueryState.from(minimalParams());
        state.incrementContinuation();
        assertEquals(1, state.continuationCount());
    }

    @Test
    void mark_compact_attempted_flips_flag() {
        var state = QueryState.from(minimalParams());
        state.markCompactAttempted();
        assertTrue(state.hasAttemptedCompact());
    }

    @Test
    void set_last_transition_is_returned_as_present_optional() {
        var state = QueryState.from(minimalParams());
        var t = new TransitionReason.MaxTokensRecovery(1);
        state.setLastTransition(t);
        assertTrue(state.lastTransition().isPresent());
        assertSame(t, state.lastTransition().get());
    }

    @Test
    void from_params_copies_max_output_tokens_override() {
        var params = new QueryParams(List.of(Message.user("q")), "sys", null, 512, null);
        var state = QueryState.from(params);
        assertEquals(512, state.maxOutputTokensOverride().orElseThrow());
    }
}
