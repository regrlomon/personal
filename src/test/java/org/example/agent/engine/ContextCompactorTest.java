package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.example.agent.tool.todo.PlanItem;
import org.example.agent.tool.todo.PlanStatus;
import org.example.agent.tool.todo.PlanningState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextCompactorTest {

    // ── Layer 1: persistIfLarge ──────────────────────────────────────

    @Test
    void persistIfLarge_shortContent_returnedUnchanged(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var content = "short content";
        assertEquals(content, compactor.persistIfLarge("id1", content));
        assertFalse(Files.exists(tempDir.resolve("id1.txt")));
    }

    @Test
    void persistIfLarge_exactlyAtThreshold_returnedUnchanged(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var content = "x".repeat(ContextCompactor.PERSIST_THRESHOLD);
        assertEquals(content, compactor.persistIfLarge("id2", content));
        assertFalse(Files.exists(tempDir.resolve("id2.txt")));
    }

    @Test
    void persistIfLarge_overThreshold_writesFileAndReturnsMarker(@TempDir Path tempDir)
            throws IOException {
        var compactor = new ContextCompactor(tempDir);
        var content = "x".repeat(ContextCompactor.PERSIST_THRESHOLD + 1);

        var result = compactor.persistIfLarge("id3", content);

        assertTrue(Files.exists(tempDir.resolve("id3.txt")));
        assertEquals(content, Files.readString(tempDir.resolve("id3.txt")));
        assertTrue(result.contains("<persisted-output>"));
        assertTrue(result.contains("id3.txt"));
        // preview is exactly the first 2000 chars
        var preview = content.substring(0, 2000);
        assertTrue(result.contains(preview));
    }
}
