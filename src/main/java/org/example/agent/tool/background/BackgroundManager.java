package org.example.agent.tool.background;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public class BackgroundManager {

    private final Path runtimeDir;
    private final ExecutorService executor;
    private final Map<String, Future<?>> futures = new HashMap<>();
    private final Map<String, RuntimeTaskRecord> records = new HashMap<>();
    private final List<BackgroundNotification> notificationQueue = new ArrayList<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final RuntimeTaskStore store;

    public BackgroundManager(Path runtimeDir) {
        this.runtimeDir = runtimeDir;
        this.store = new RuntimeTaskStore(runtimeDir);
        this.executor = Executors.newCachedThreadPool(r -> {
            var t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
    }

    public String submit(BackgroundTask task) {
        var id = newId();
        var outputFile = runtimeDir.resolve(id + ".log");
        var record = new RuntimeTaskRecord(id, task.describe(), RuntimeTaskStatus.RUNNING,
                System.currentTimeMillis(), "", outputFile);
        lock.lock();
        try {
            records.put(id, record);
            store.save(record);
        } finally {
            lock.unlock();
        }
        var future = executor.submit(() -> {
            try {
                task.execute(outputFile);
                var preview = task.preview(outputFile);
                updateRecord(id, RuntimeTaskStatus.COMPLETED, preview);
            } catch (TimeoutException e) {
                updateRecord(id, RuntimeTaskStatus.TIMEOUT, "Command timed out");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // cancelled externally — record already updated by cancel()
            } catch (Exception e) {
                var msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                updateRecord(id, RuntimeTaskStatus.FAILED, msg);
            }
            return null;
        });
        lock.lock();
        try {
            futures.put(id, future);
        } finally {
            lock.unlock();
        }
        return id;
    }

    public RuntimeTaskRecord check(String id) {
        lock.lock();
        try {
            return records.get(id);
        } finally {
            lock.unlock();
        }
    }

    public List<RuntimeTaskRecord> list() {
        lock.lock();
        try {
            return records.values().stream()
                    .sorted(Comparator.comparingLong(RuntimeTaskRecord::startedAt))
                    .collect(Collectors.toList());
        } finally {
            lock.unlock();
        }
    }

    public boolean cancel(String id) {
        lock.lock();
        try {
            var record = records.get(id);
            if (record == null || record.status() != RuntimeTaskStatus.RUNNING) return false;
            var future = futures.get(id);
            if (future != null) future.cancel(true);
            var cancelled = new RuntimeTaskRecord(id, record.description(),
                    RuntimeTaskStatus.CANCELLED, record.startedAt(), "", record.outputFile());
            records.put(id, cancelled);
            store.save(cancelled);
            notificationQueue.add(new BackgroundNotification(
                    id, record.description(), RuntimeTaskStatus.CANCELLED, ""));
            return true;
        } finally {
            lock.unlock();
        }
    }

    public List<BackgroundNotification> drain() {
        lock.lock();
        try {
            var result = List.copyOf(notificationQueue);
            notificationQueue.clear();
            return result;
        } finally {
            lock.unlock();
        }
    }

    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            // Give the OS a moment to release file handles after process kills (Windows)
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void updateRecord(String id, RuntimeTaskStatus status, String preview) {
        lock.lock();
        try {
            var current = records.get(id);
            if (current == null || current.status() != RuntimeTaskStatus.RUNNING) return;
            var updated = new RuntimeTaskRecord(id, current.description(), status,
                    current.startedAt(), preview, current.outputFile());
            records.put(id, updated);
            store.save(updated);
            notificationQueue.add(new BackgroundNotification(
                    id, current.description(), status, preview));
        } finally {
            lock.unlock();
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }
}
