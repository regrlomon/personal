package org.example.agent.tool.background;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BackgroundManagerTest {

    @TempDir Path tempDir;

    @Test
    void shell_task_writes_output_to_file() throws Exception {
        var outFile = tempDir.resolve("out.log");
        var task = new ShellBackgroundTask("echo hello_world", 10);
        task.execute(outFile);
        var content = Files.readString(outFile);
        assertTrue(content.contains("hello_world"));
    }

    @Test
    void shell_task_preview_returns_first_500_chars() throws Exception {
        var outFile = tempDir.resolve("out.log");
        Files.writeString(outFile, "a".repeat(600));
        var task = new ShellBackgroundTask("echo x", 10);
        var preview = task.preview(outFile);
        assertEquals(503, preview.length()); // 500 + "..."
        assertTrue(preview.endsWith("..."));
    }

    @Test
    void callable_task_writes_return_value_to_file() throws Exception {
        var outFile = tempDir.resolve("out.log");
        var task = new CallableBackgroundTask("compute", () -> "result_42");
        task.execute(outFile);
        assertEquals("result_42", Files.readString(outFile).trim());
    }

    @Test
    void submit_returns_id_and_task_is_initially_running() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new ShellBackgroundTask("sleep 10", 30));
        var record = manager.check(id);
        assertNotNull(record);
        assertEquals(RuntimeTaskStatus.RUNNING, record.status());
        manager.shutdown();
    }

    @Test
    void completed_task_appears_in_drain() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new ShellBackgroundTask("echo done", 10));
        waitForStatus(manager, id, RuntimeTaskStatus.COMPLETED, 3000);
        var notifications = manager.drain();
        assertEquals(1, notifications.size());
        assertEquals(id, notifications.get(0).taskId());
        assertEquals(RuntimeTaskStatus.COMPLETED, notifications.get(0).status());
        assertTrue(notifications.get(0).preview().contains("done"));
        manager.shutdown();
    }

    @Test
    void drain_clears_queue_on_second_call() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new ShellBackgroundTask("echo x", 10));
        waitForStatus(manager, id, RuntimeTaskStatus.COMPLETED, 3000);
        manager.drain();
        assertTrue(manager.drain().isEmpty());
        manager.shutdown();
    }

    @Test
    void failed_task_appears_in_drain_with_failed_status() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new CallableBackgroundTask("fail", () -> {
            throw new RuntimeException("intentional failure");
        }));
        waitForStatus(manager, id, RuntimeTaskStatus.FAILED, 3000);
        var notifications = manager.drain();
        assertEquals(1, notifications.size());
        assertEquals(RuntimeTaskStatus.FAILED, notifications.get(0).status());
        manager.shutdown();
    }

    @Test
    void list_returns_all_submitted_tasks() throws Exception {
        var manager = new BackgroundManager(tempDir);
        manager.submit(new ShellBackgroundTask("echo a", 10));
        manager.submit(new ShellBackgroundTask("echo b", 10));
        var list = manager.list();
        assertEquals(2, list.size());
        manager.shutdown();
    }

    @Test
    void cancel_cancels_running_task() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new ShellBackgroundTask("sleep 30", 60));
        Thread.sleep(100);
        var cancelled = manager.cancel(id);
        assertTrue(cancelled);
        assertEquals(RuntimeTaskStatus.CANCELLED, manager.check(id).status());
        manager.shutdown();
    }

    @Test
    void cancel_returns_false_for_completed_task() throws Exception {
        var manager = new BackgroundManager(tempDir);
        var id = manager.submit(new ShellBackgroundTask("echo x", 10));
        waitForStatus(manager, id, RuntimeTaskStatus.COMPLETED, 3000);
        assertFalse(manager.cancel(id));
        manager.shutdown();
    }

    private void waitForStatus(BackgroundManager manager, String id,
                                RuntimeTaskStatus expected, long timeoutMs)
            throws InterruptedException {
        var deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            var record = manager.check(id);
            if (record != null && record.status() == expected) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timed out waiting for status " + expected +
                ", current: " + manager.check(id).status());
    }
}
