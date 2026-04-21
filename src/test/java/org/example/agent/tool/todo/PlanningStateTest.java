package org.example.agent.tool.todo;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlanningStateTest {

    @Test
    void initial_state_is_empty_with_zero_rounds() {
        var state = new PlanningState();
        assertTrue(state.items().isEmpty());
        assertEquals(0, state.roundsSinceUpdate());
    }

    @Test
    void update_replaces_items_and_resets_counter() {
        var state = new PlanningState();
        state.tickRound();
        state.tickRound();
        var items = List.of(new PlanItem("step 1", PlanStatus.IN_PROGRESS, "doing step 1"));
        state.update(items);
        assertEquals(1, state.items().size());
        assertEquals(0, state.roundsSinceUpdate());
    }

    @Test
    void update_rejects_multiple_in_progress() {
        var state = new PlanningState();
        var items = List.of(
            new PlanItem("a", PlanStatus.IN_PROGRESS, "doing a"),
            new PlanItem("b", PlanStatus.IN_PROGRESS, "doing b")
        );
        assertThrows(IllegalArgumentException.class, () -> state.update(items));
    }

    @Test
    void tickRound_increments_counter() {
        var state = new PlanningState();
        state.tickRound();
        state.tickRound();
        assertEquals(2, state.roundsSinceUpdate());
    }

    @Test
    void needsReminder_false_below_threshold() {
        var state = new PlanningState();
        state.tickRound();
        state.tickRound();
        assertFalse(state.needsReminder());
    }

    @Test
    void needsReminder_true_at_threshold() {
        var state = new PlanningState();
        state.tickRound();
        state.tickRound();
        state.tickRound();
        assertTrue(state.needsReminder());
    }

    @Test
    void render_formats_items_with_correct_markers() {
        var state = new PlanningState();
        state.update(List.of(
            new PlanItem("read test", PlanStatus.PENDING, ""),
            new PlanItem("fix bug", PlanStatus.IN_PROGRESS, "fixing bug"),
            new PlanItem("write docs", PlanStatus.COMPLETED, "")
        ));
        var rendered = state.render();
        assertTrue(rendered.contains("[ ] read test"));
        assertTrue(rendered.contains("[>] fix bug"));
        assertTrue(rendered.contains("[x] write docs"));
    }
}
