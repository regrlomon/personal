package org.example.agent.tool.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskManagerTest {

    @TempDir Path tempDir;
    private TaskManager manager;

    @BeforeEach
    void setUp() {
        manager = new TaskManager(tempDir);
    }

    @Test
    void create_assigns_sequential_ids() {
        var t1 = manager.create("A", "", List.of());
        var t2 = manager.create("B", "", List.of());
        assertEquals(1, t1.id());
        assertEquals(2, t2.id());
    }

    @Test
    void create_with_blockedBy_links_both_sides() {
        var t1 = manager.create("Parser", "", List.of());
        var t2 = manager.create("Checker", "", List.of(t1.id()));

        assertEquals(List.of(t1.id()), t2.blockedBy());
        assertEquals(List.of(t2.id()), manager.get(t1.id()).blocks());
    }

    @Test
    void create_throws_NoSuchTaskException_for_invalid_blockedBy() {
        assertThrows(NoSuchTaskException.class, () -> manager.create("X", "", List.of(99)));
    }

    @Test
    void isReady_true_when_pending_and_no_blockedBy() {
        var t = manager.create("Task", "", List.of());
        assertTrue(manager.isReady(t));
    }

    @Test
    void isReady_false_when_blockedBy_is_not_empty() {
        var t1 = manager.create("A", "", List.of());
        var t2 = manager.create("B", "", List.of(t1.id()));
        assertFalse(manager.isReady(t2));
    }

    @Test
    void complete_auto_unlocks_downstream_tasks() {
        var t1 = manager.create("A", "", List.of());
        var t2 = manager.create("B", "", List.of(t1.id()));

        var result = manager.update(t1.id(),
                new TaskPatch(TaskStatus.COMPLETED, null, null, null, List.of(), List.of()));

        assertEquals(List.of(t2.id()), result.unblocked());
        assertTrue(manager.isReady(manager.get(t2.id())));
    }

    @Test
    void complete_is_idempotent() {
        var t = manager.create("A", "", List.of());
        var patch = new TaskPatch(TaskStatus.COMPLETED, null, null, null, List.of(), List.of());

        manager.update(t.id(), patch);
        assertDoesNotThrow(() -> manager.update(t.id(), patch));
    }

    @Test
    void update_changes_only_provided_fields() {
        var t = manager.create("Original", "desc", List.of());
        var result = manager.update(t.id(),
                new TaskPatch(TaskStatus.IN_PROGRESS, null, null, "agent-x", List.of(), List.of()));

        var updated = result.task();
        assertEquals("Original", updated.subject());
        assertEquals("desc", updated.description());
        assertEquals(TaskStatus.IN_PROGRESS, updated.status());
        assertEquals("agent-x", updated.owner());
    }

    @Test
    void update_throws_NoSuchTaskException_for_missing_id() {
        assertThrows(NoSuchTaskException.class, () ->
                manager.update(99, new TaskPatch(null, null, null, null, List.of(), List.of())));
    }

    @Test
    void list_returns_all_tasks_sorted_by_id() {
        manager.create("A", "", List.of());
        manager.create("B", "", List.of());
        var list = manager.list();
        assertEquals(2, list.size());
        assertEquals(1, list.get(0).id());
        assertEquals(2, list.get(1).id());
    }
}
