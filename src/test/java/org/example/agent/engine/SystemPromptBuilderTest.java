package org.example.agent.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.example.agent.core.MemoryEntry;
import org.example.agent.tool.skill.SkillDocument;
import org.example.agent.tool.skill.SkillManifest;
import org.example.agent.tool.skill.SkillRegistry;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptBuilderTest {

    @TempDir
    Path tempDir;

    private SystemPromptBuilder builder() {
        return new SystemPromptBuilder(null, null, tempDir.toString());
    }

    @Test
    void buildCore_returns_core_unchanged() {
        assertEquals("hello", builder().buildCore("hello"));
    }

    @Test
    void buildCore_returns_empty_for_null() {
        assertEquals("", builder().buildCore(null));
    }

    @Test
    void buildTools_returns_empty() {
        assertEquals("", builder().buildTools());
    }

    @Test
    void buildDynamic_contains_today_and_cwd() {
        var dynamic = builder().buildDynamic();
        assertTrue(dynamic.startsWith("=== Dynamic Context ==="),
                "must start with header");
        assertTrue(dynamic.contains("Date: " + LocalDate.now()),
                "must contain today's date");
        assertTrue(dynamic.contains("CWD:  " + tempDir),
                "must contain cwd");
    }

    // ---- buildSkills ----

    @Test
    void buildSkills_returns_empty_when_registry_is_null() {
        assertEquals("", builder().buildSkills());
    }

    @Test
    void buildSkills_returns_description_from_registry() {
        var doc = new SkillDocument(
                new SkillManifest("my-skill", "Does stuff"), "body");
        var registry = new SkillRegistry(java.util.Map.of("my-skill", doc));
        var b = new SystemPromptBuilder(registry, null, tempDir.toString());
        assertTrue(b.buildSkills().contains("my-skill: Does stuff"));
    }

    // ---- buildMemory ----

    @Test
    void buildMemory_returns_empty_when_store_is_null() {
        assertEquals("", builder().buildMemory());
    }

    @Test
    void buildMemory_returns_empty_when_store_has_no_entries() throws Exception {
        var store = new MemoryStore(tempDir.resolve(".memory"));
        var b = new SystemPromptBuilder(null, store, tempDir.toString());
        assertEquals("", b.buildMemory());
    }

    @Test
    void buildMemory_formats_entries_under_memories_header() throws Exception {
        var store = new MemoryStore(tempDir.resolve(".memory"));
        store.save(new MemoryEntry(
                "key1", "desc", "user", "Remember this."));
        var b = new SystemPromptBuilder(null, store, tempDir.toString());
        var result = b.buildMemory();
        assertTrue(result.startsWith("## Memories"));
        assertTrue(result.contains("### key1 [user]"), "heading must include ### prefix");
        assertTrue(result.contains("key1 [user]"));
        assertTrue(result.contains("Remember this."));
    }

    @Test
    void buildMemory_returns_empty_on_io_exception() throws Exception {
        // If memoryDir is a file rather than a directory, Files.list() throws NotDirectoryException
        var notADir = tempDir.resolve(".bad-memory");
        java.nio.file.Files.writeString(notADir, "not a directory");
        var store = new MemoryStore(notADir);
        var b = new SystemPromptBuilder(null, store, tempDir.toString());
        assertEquals("", b.buildMemory());
    }
}
