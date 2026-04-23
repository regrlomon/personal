package org.example.agent.engine;

import org.example.agent.core.MemoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    @TempDir
    Path memoryDir;

    MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(memoryDir);
    }

    @Test
    void memoryEntry_stores_fields() {
        var entry = new MemoryEntry("pref_tabs", "Use tabs", "user", "Always use tabs.");
        assertEquals("pref_tabs", entry.name());
        assertEquals("Use tabs", entry.description());
        assertEquals("user", entry.type());
        assertEquals("Always use tabs.", entry.content());
    }

    @Test
    void loadAll_returns_empty_when_directory_absent() throws IOException {
        var store2 = new MemoryStore(memoryDir.resolve("nonexistent"));
        assertEquals(0, store2.loadAll().size());
    }

    @Test
    void save_creates_file_and_can_be_loaded() throws IOException {
        var entry = new MemoryEntry("pref_tabs", "Use tabs", "user", "Always use tabs.");
        store.save(entry);

        var loaded = store.loadAll();
        assertEquals(1, loaded.size());
        var e = loaded.get(0);
        assertEquals("pref_tabs", e.name());
        assertEquals("Use tabs", e.description());
        assertEquals("user", e.type());
        assertEquals("Always use tabs.", e.content());
    }

    @Test
    void save_overwrites_existing_entry_with_same_name() throws IOException {
        store.save(new MemoryEntry("note", "A note", "project", "Original content."));
        store.save(new MemoryEntry("note", "A note updated", "project", "Updated content."));

        var loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("Updated content.", loaded.get(0).content());
        assertEquals("A note updated", loaded.get(0).description());
    }

    @Test
    void save_creates_memory_dir_if_absent() throws IOException {
        var nested = memoryDir.resolve("sub").resolve(".memory");
        var s = new MemoryStore(nested);
        s.save(new MemoryEntry("x", "desc", "user", "body"));
        assertTrue(Files.exists(nested));
    }

    @Test
    void save_rebuilds_index_file() throws IOException {
        store.save(new MemoryEntry("key1", "First entry", "feedback", "content1"));
        store.save(new MemoryEntry("key2", "Second entry", "project", "content2"));

        var index = Files.readString(memoryDir.resolve("MEMORY.md"));
        assertTrue(index.contains("key1"));
        assertTrue(index.contains("First entry"));
        assertTrue(index.contains("key2"));
        assertTrue(index.contains("Second entry"));
    }

    @Test
    void loadAll_skips_files_with_malformed_frontmatter() throws IOException {
        store.save(new MemoryEntry("good", "Good", "user", "body"));
        Files.writeString(memoryDir.resolve("bad.md"), "no frontmatter here");

        var loaded = store.loadAll();
        assertEquals(1, loaded.size());
        assertEquals("good", loaded.get(0).name());
    }
}
