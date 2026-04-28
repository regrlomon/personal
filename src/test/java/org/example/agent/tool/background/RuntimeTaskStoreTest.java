package org.example.agent.tool.background;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class RuntimeTaskStoreTest {

    @TempDir Path tempDir;
    private RuntimeTaskStore store;

    @BeforeEach
    void setUp() {
        store = new RuntimeTaskStore(tempDir);
    }

    @Test
    void save_creates_json_file_and_load_returns_equal_record() {
        var record = new RuntimeTaskRecord("a1b2c3d4", "pytest -v",
                RuntimeTaskStatus.COMPLETED, 1710000000000L,
                "5 passed", tempDir.resolve("a1b2c3d4.log"));
        store.save(record);

        assertTrue(java.nio.file.Files.exists(tempDir.resolve("a1b2c3d4.json")));
        var loaded = store.load("a1b2c3d4");
        assertEquals(record, loaded);
    }

    @Test
    void load_returns_null_for_missing_id() {
        assertNull(store.load("nonexistent"));
    }

    @Test
    void loadAll_returns_all_saved_records() {
        var r1 = new RuntimeTaskRecord("aaa00001", "cmd1", RuntimeTaskStatus.RUNNING,
                1000L, "", tempDir.resolve("aaa00001.log"));
        var r2 = new RuntimeTaskRecord("bbb00002", "cmd2", RuntimeTaskStatus.COMPLETED,
                2000L, "ok", tempDir.resolve("bbb00002.log"));
        store.save(r1);
        store.save(r2);

        var all = store.loadAll();
        assertEquals(2, all.size());
    }

    @Test
    void serialize_roundtrips_all_fields() {
        var record = new RuntimeTaskRecord("a1b2c3d4", "npm install",
                RuntimeTaskStatus.FAILED, 1710000000000L,
                "error: package not found",
                Paths.get("/tmp/.runtime-tasks/a1b2c3d4.log"));
        var json = RuntimeTaskStore.serialize(record);
        var loaded = RuntimeTaskStore.deserialize(json);
        assertEquals(record, loaded);
    }

    @Test
    void serialize_escapes_quotes_in_description() {
        var record = new RuntimeTaskRecord("a1b2c3d4", "echo \"hello\"",
                RuntimeTaskStatus.COMPLETED, 0L, "", tempDir.resolve("a1b2c3d4.log"));
        var json = RuntimeTaskStore.serialize(record);
        var loaded = RuntimeTaskStore.deserialize(json);
        assertEquals("echo \"hello\"", loaded.description());
    }
}
