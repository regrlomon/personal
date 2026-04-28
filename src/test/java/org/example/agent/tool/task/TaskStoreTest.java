package org.example.agent.tool.task;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskStoreTest {

    @TempDir Path tempDir;
    private TaskStore store;

    @BeforeEach
    void setUp() {
        store = new TaskStore(tempDir);
    }

    @Test
    void save_creates_file_and_load_returns_equal_record() {
        var task = new TaskRecord(1, "Write parser", "details", TaskStatus.PENDING,
                List.of(), List.of(2), "");
        store.save(task);

        assertTrue(Files.exists(tempDir.resolve("task_1.json")));
        var loaded = store.load(1);
        assertEquals(task, loaded);
    }

    @Test
    void load_throws_NoSuchTaskException_for_missing_id() {
        assertThrows(NoSuchTaskException.class, () -> store.load(99));
    }

    @Test
    void nextId_returns_1_when_directory_is_empty() {
        assertEquals(1, store.nextId());
    }

    @Test
    void nextId_returns_max_plus_one() throws IOException {
        Files.writeString(tempDir.resolve("task_1.json"),
                TaskStore.serialize(new TaskRecord(1, "A", "", TaskStatus.PENDING, List.of(), List.of(), "")));
        Files.writeString(tempDir.resolve("task_3.json"),
                TaskStore.serialize(new TaskRecord(3, "C", "", TaskStatus.PENDING, List.of(), List.of(), "")));

        assertEquals(4, store.nextId());
    }

    @Test
    void loadAll_returns_all_records_sorted_by_id() throws IOException {
        Files.writeString(tempDir.resolve("task_2.json"),
                TaskStore.serialize(new TaskRecord(2, "B", "", TaskStatus.IN_PROGRESS, List.of(), List.of(), "")));
        Files.writeString(tempDir.resolve("task_1.json"),
                TaskStore.serialize(new TaskRecord(1, "A", "", TaskStatus.PENDING, List.of(), List.of(), "")));

        var all = store.loadAll();
        assertEquals(2, all.size());
        assertEquals(1, all.get(0).id());
        assertEquals(2, all.get(1).id());
    }

    @Test
    void serialize_roundtrips_all_fields() {
        var task = new TaskRecord(5, "my task", "desc with \"quotes\"",
                TaskStatus.COMPLETED, List.of(1, 2), List.of(3), "agent-x");
        var json = TaskStore.serialize(task);
        var loaded = TaskStore.deserialize(json);
        assertEquals(task, loaded);
    }

    @Test
    void serialize_handles_empty_lists() {
        var task = new TaskRecord(1, "t", "", TaskStatus.PENDING, List.of(), List.of(), "");
        var json = TaskStore.serialize(task);
        var loaded = TaskStore.deserialize(json);
        assertEquals(task, loaded);
    }
}
