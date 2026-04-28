package org.example.agent.tool.task;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class TaskManager {

    private final TaskStore store;

    public TaskManager(Path tasksDir) {
        this.store = new TaskStore(tasksDir);
    }

    public TaskRecord create(String subject, String description, List<Integer> blockedBy) {
        Objects.requireNonNull(subject, "subject must not be null");
        if (subject.isBlank()) throw new IllegalArgumentException("subject must not be blank");
        Objects.requireNonNull(description, "description must not be null");

        for (int bid : blockedBy) store.load(bid); // validate all exist

        int id = store.nextId();
        var record = new TaskRecord(id, subject, description, TaskStatus.PENDING,
                new ArrayList<>(blockedBy), new ArrayList<>(), "");
        store.save(record);

        for (int bid : blockedBy) {
            var blocker = store.load(bid);
            var newBlocks = new ArrayList<>(blocker.blocks());
            if (!newBlocks.contains(id)) newBlocks.add(id);
            store.save(new TaskRecord(blocker.id(), blocker.subject(), blocker.description(),
                    blocker.status(), blocker.blockedBy(), newBlocks, blocker.owner()));
        }

        return record;
    }

    public UpdateResult update(int id, TaskPatch patch) {
        var task = store.load(id);

        for (int bid : patch.addBlockedBy()) store.load(bid);
        for (int bid : patch.addBlocks())    store.load(bid);

        var newSubject     = patch.subject()     != null ? patch.subject()     : task.subject();
        var newDescription = patch.description() != null ? patch.description() : task.description();
        var newOwner       = patch.owner()       != null ? patch.owner()       : task.owner();
        var newStatus      = patch.status()      != null ? patch.status()      : task.status();

        var newBlockedBy = new ArrayList<>(task.blockedBy());
        for (int bid : patch.addBlockedBy()) if (!newBlockedBy.contains(bid)) newBlockedBy.add(bid);

        var newBlocks = new ArrayList<>(task.blocks());
        for (int bid : patch.addBlocks()) if (!newBlocks.contains(bid)) newBlocks.add(bid);

        for (int bid : patch.addBlockedBy()) {
            var blocker = store.load(bid);
            var bBlocks = new ArrayList<>(blocker.blocks());
            if (!bBlocks.contains(id)) {
                bBlocks.add(id);
                store.save(new TaskRecord(blocker.id(), blocker.subject(), blocker.description(),
                        blocker.status(), blocker.blockedBy(), bBlocks, blocker.owner()));
            }
        }

        for (int bid : patch.addBlocks()) {
            var blockee = store.load(bid);
            var bBlockedBy = new ArrayList<>(blockee.blockedBy());
            if (!bBlockedBy.contains(id)) {
                bBlockedBy.add(id);
                store.save(new TaskRecord(blockee.id(), blockee.subject(), blockee.description(),
                        blockee.status(), bBlockedBy, blockee.blocks(), blockee.owner()));
            }
        }

        var updated = new TaskRecord(id, newSubject, newDescription, newStatus,
                newBlockedBy, newBlocks, newOwner);
        store.save(updated);

        List<Integer> unblocked = List.of();
        if (newStatus == TaskStatus.COMPLETED && task.status() != TaskStatus.COMPLETED) {
            unblocked = autoUnlock(updated);
        }

        return new UpdateResult(updated, unblocked);
    }

    public TaskRecord get(int id) {
        return store.load(id);
    }

    public List<TaskRecord> list() {
        return store.loadAll();
    }

    public boolean isReady(TaskRecord t) {
        return t.status() == TaskStatus.PENDING && t.blockedBy().isEmpty();
    }

    private List<Integer> autoUnlock(TaskRecord completed) {
        var unblocked = new ArrayList<Integer>();
        for (int blockeeId : completed.blocks()) {
            try {
                var blockee = store.load(blockeeId);
                var newBlockedBy = new ArrayList<>(blockee.blockedBy());
                newBlockedBy.remove(Integer.valueOf(completed.id()));
                var updated = new TaskRecord(blockee.id(), blockee.subject(), blockee.description(),
                        blockee.status(), newBlockedBy, blockee.blocks(), blockee.owner());
                store.save(updated);
                if (isReady(updated)) unblocked.add(blockeeId);
            } catch (NoSuchTaskException ignored) {
                // blockee deleted, skip
            }
        }
        return List.copyOf(unblocked);
    }
}
