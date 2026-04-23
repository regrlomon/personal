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

    // ── Layer 2: microCompact ────────────────────────────────────────

    @Test
    void microCompact_fewerThanThreshold_returnsUnchanged(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var messages = List.of(
                Message.user("task"),
                toolResultMsg("id1", "r1"),
                toolResultMsg("id2", "r2"),
                toolResultMsg("id3", "r3")  // exactly MICRO_KEEP_RECENT
        );

        var result = compactor.microCompact(messages);

        assertEquals("r1", toolResultContent(result, 1));
        assertEquals("r3", toolResultContent(result, 3));
    }

    @Test
    void microCompact_olderResultsReplacedWithPlaceholder(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var messages = List.of(
                Message.user("task"),
                toolResultMsg("id1", "old1"),  // to be compacted
                toolResultMsg("id2", "old2"),  // to be compacted
                toolResultMsg("id3", "r3"),    // keep
                toolResultMsg("id4", "r4"),    // keep
                toolResultMsg("id5", "r5")     // keep
        );

        var result = compactor.microCompact(messages);

        assertEquals(ContextCompactor.PLACEHOLDER, toolResultContent(result, 1));
        assertEquals(ContextCompactor.PLACEHOLDER, toolResultContent(result, 2));
        assertEquals("r3", toolResultContent(result, 3));
        assertEquals("r5", toolResultContent(result, 5));
    }

    @Test
    void microCompact_nonToolMessages_leftUntouched(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var msg = Message.user("plain text");

        var result = compactor.microCompact(List.of(msg));

        assertEquals(1, result.size());
        assertEquals(msg, result.get(0));
    }

    @Test
    void microCompact_returnsNewList_doesNotMutateOriginal(@TempDir Path tempDir) {
        var compactor = new ContextCompactor(tempDir);
        var original = new java.util.ArrayList<>(List.of(
                Message.user("task"),
                toolResultMsg("a", "val"),
                toolResultMsg("b", "val"),
                toolResultMsg("c", "val"),
                toolResultMsg("d", "val")  // 4 tool-result messages > MICRO_KEEP_RECENT
        ));

        compactor.microCompact(original);

        // original unchanged
        assertEquals("val", toolResultContent(original, 1));
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static Message toolResultMsg(String id, String content) {
        return new Message(Role.USER, List.of(new ContentBlock.ToolResult(id, content)));
    }

    private static String toolResultContent(List<Message> messages, int idx) {
        return ((ContentBlock.ToolResult) messages.get(idx).content().get(0)).content();
    }
}
