# S12 Task System Design

**Date:** 2026-04-28  
**Chapter:** s12-task-system  
**Status:** Approved

---

## Overview

Upgrade the agent from a session-scoped todo list (s03) to a persistent, dependency-aware task graph. The core capability added is: tasks survive JVM restarts, and the system can answer "who is ready to start now" based on dependency resolution.

---

## Data Model

### `TaskStatus` (enum)

```
PENDING → IN_PROGRESS → COMPLETED
DELETED  (logical delete, excluded from workflow)
```

### `TaskRecord` (Java record, immutable)

```java
record TaskRecord(
    int           id,
    String        subject,
    String        description,
    TaskStatus    status,
    List<Integer> blockedBy,
    List<Integer> blocks,
    String        owner
)
```

### Ready Rule

```java
boolean isReady(TaskRecord t) {
    return t.status() == PENDING && t.blockedBy().isEmpty();
}
```

### Disk Format

Each task is stored as `.tasks/task_{id}.json` relative to the working directory.

```json
{
  "id": 1,
  "subject": "Write parser",
  "description": "",
  "status": "pending",
  "blockedBy": [],
  "blocks": [2],
  "owner": ""
}
```

---

## Components

### File Structure

```
tool/task/
  TaskRecord.java         ← data record + TaskStatus enum
  TaskStore.java          ← file I/O: load / save / loadAll / nextId
  TaskManager.java        ← business logic: create / update / complete (auto-unlock) / get / list / isReady
  NoSuchTaskException.java
  TaskCreateTool.java
  TaskUpdateTool.java
  TaskGetTool.java
  TaskListTool.java

tool/subagent/
  SubagentTool.java       ← relocated from tool/task/TaskTool.java, tool name changed to "subagent"
```

### Responsibility Boundaries

| Class | Responsibility | Depends On |
|---|---|---|
| `TaskStore` | Read/write `.tasks/` JSON files; allocate auto-increment IDs | `java.nio.file` (hand-rolled JSON, no external library) |
| `TaskManager` | Create tasks; update fields; complete with auto-unlock; isReady | `TaskStore` |
| `Task*Tool` | Parse `Map<String,Object>` input; call `TaskManager`; return `ToolResultEnvelope` | `ctx.taskManager()` |

### `ToolUseContext` Changes

Add `taskManager` field alongside `planningState`. Follow the existing builder pattern:

```java
public TaskManager taskManager() { return taskManager; }

public ToolUseContext withTaskManager(TaskManager taskManager) { ... }
```

---

## Data Flow & Persistence

**JSON serialization:** Hand-rolled (no external library — project has zero runtime dependencies). `TaskRecord` is a flat structure with only `int`, `String`, and `List<Integer>` fields — serialization extends the pattern already used in `ShellHookRunner.toJson()`. Deserialization uses simple regex extraction on the flat key-value JSON.  
`TaskStore` accepts `Path tasksDir` and calls `Files.createDirectories()` on construction if the directory is absent.

**ID allocation:** `nextId()` scans `.tasks/`, finds the max existing ID, returns max + 1. No separate counter file.

**Concurrency:** Single-threaded agent loop — no file locking required.

**Operation flows:**

```
task_create
  → TaskManager.create(subject, description, blockedBy)
    → TaskStore.nextId()
    → assemble TaskRecord
    → TaskStore.save(record)

task_update (including complete)
  → TaskManager.update(id, patch)
    → TaskStore.load(id)
    → merge patch fields
    → TaskStore.save(record)
    → if status → COMPLETED:
        for each id in record.blocks():
          load blockee → remove id from blockedBy → save blockee

task_get
  → TaskManager.get(id)
    → TaskStore.load(id)

task_list
  → TaskManager.list()
    → TaskStore.loadAll()   // scan dir, read all files
    → sort by id, return List<TaskRecord>
```

---

## Tool API

### `task_create`

```
Input:  { "subject": string (required), "description": string (optional), "blockedBy": [int, ...] (optional) }
Output: "Created task #1: Write parser"
```

### `task_update`

```
Input: {
  "id":          int    (required),
  "status":      "pending|in_progress|completed|deleted" (optional),
  "subject":     string (optional),
  "description": string (optional),
  "owner":       string (optional),
  "addBlockedBy": [int, ...] (optional),   ← append semantics
  "addBlocks":    [int, ...]  (optional)   ← append semantics
}
Output: "Updated task #1"
        "Completed task #1; unblocked: [2, 3]"  (when status → completed)
```

`addBlockedBy` / `addBlocks` append to existing lists; they do not overwrite. Both sides of the dependency are maintained simultaneously.

### `task_get`

```
Input:  { "id": int (required) }
Output: multi-line text:
  #1 [pending] Write parser
  blockedBy: []  blocks: [2]  owner: ""
  <description if non-empty>
```

### `task_list`

```
Input:  {} (no parameters)
Output: one task per line:
  #1 [pending]     Write parser
  #2 [in_progress] Semantic check    owner: agent-a
  #3 [pending*]    Write tests        (* = ready: no blockedBy)
```

`[pending*]` marks tasks that are ready to start — the model can identify actionable work at a glance.

---

## SubagentTool Migration

| Item | Before | After |
|---|---|---|
| File path | `tool/task/TaskTool.java` | `tool/subagent/SubagentTool.java` |
| Class name | `TaskTool` | `SubagentTool` |
| Tool name | `"task"` | `"subagent"` |
| Internal logic | unchanged | unchanged |

Registration site: replace `new TaskTool(...)` with `new SubagentTool(...)`.

---

## Error Handling

All exceptions are caught at the tool layer and returned as `ToolResultEnvelope.error(msg)`. They are never thrown upward.

| Scenario | Handling |
|---|---|
| `task_get` / `task_update` with non-existent id | `TaskStore.load()` throws `NoSuchTaskException`; tool catches → `error("Task #N not found")` |
| `task_create` with blank subject | Tool-layer validation → `error("subject must not be blank")` |
| Invalid status value | Tool-layer switch → `error("Unknown status: X")` |
| `addBlockedBy` references non-existent id | `TaskManager` validates each id → `error("Task #N not found")` |
| File I/O failure | `TaskStore` throws `UncheckedIOException`; tool catches → `error("IO error: ...")` |
| `complete` on already-completed task | Silent success (idempotent) |

`NoSuchTaskException extends RuntimeException` lives in `tool/task/`.

---

## Testing Strategy

**Layer 1 — `TaskStore` unit tests** (JUnit 5 `@TempDir`)
- After `save`, file exists and deserializes correctly
- `load` with unknown id throws `NoSuchTaskException`
- `nextId` with existing task_1 and task_3 returns 4

**Layer 2 — `TaskManager` business logic tests**
- `complete(A)` removes A from `blockedBy` of all tasks in A's `blocks` list
- `isReady`: pending + empty blockedBy → true; non-empty blockedBy → false
- `complete` is idempotent: no error, no side effects on already-completed task

**Layer 3 — Tool integration tests** (`ToolUseContext` + real `TaskManager`)
- `task_create` with blank subject → `envelope.isError() == true`
- `task_update` with non-existent id → error envelope
- `task_list` output: ready tasks carry `*` marker

No file I/O mocking — tests hit the real filesystem via `@TempDir`, consistent with s12's teaching goal that persistence is the core capability.
