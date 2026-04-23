# Memory System Design

**Date**: 2026-04-23  
**Reference**: `src/main/resources/zh/s09-memory-system.md`  
**Status**: Approved

---

## 1. Problem Statement

Without persistent memory, every new session starts from zero. The agent forgets:

- User long-term preferences
- Corrections the user has made multiple times
- Project conventions not obvious from code
- Pointers to external resources

Memory is not a general-purpose store — only information that remains valuable across sessions and cannot easily be re-derived from the current repository state belongs here.

---

## 2. Architecture

### Components

```
org.example.agent/
├── core/
│   └── MemoryEntry.java          ← immutable record (name, description, type, content)
├── engine/
│   ├── MemoryStore.java          ← file I/O service: save / delete / loadAll / rebuildIndex
│   └── QueryEngine.java          ← [modified] inject MemoryStore, build memory section at startup
└── tool/
    ├── SaveMemoryTool.java        ← Tool impl, delegates to MemoryStore
    └── DeleteMemoryTool.java      ← Tool impl, delegates to MemoryStore
```

### Data Flow

```
Session start
  └─ QueryEngine.buildMemorySection()
       └─ MemoryStore.loadAll()
            └─ scan {workDir}/.memory/*.md
                 └─ parse frontmatter → List<MemoryEntry>
                      └─ format as "## Memories\n..." → injected into systemPrompt

Agent running
  └─ Agent calls save_memory / delete_memory tool
       └─ SaveMemoryTool / DeleteMemoryTool
            └─ MemoryStore.save(entry) / MemoryStore.delete(name)
                 └─ write file + rebuildIndex() → updates .memory/MEMORY.md
```

---

## 3. Data Structures

### `MemoryEntry` (immutable record)

```java
record MemoryEntry(
    String name,         // unique identifier; becomes filename: {name}.md
    String description,  // one-line summary written to MEMORY.md index
    String type,         // "user" | "feedback" | "project" | "reference"
    String content       // body text
) {}
```

### Disk Format — `.memory/{name}.md`

```markdown
---
name: prefer_concise_responses
description: User prefers terse, no-trailing-summary responses
type: feedback
---
Do not add summaries at the end of responses. The user reads diffs directly.
```

### Index File — `.memory/MEMORY.md` (auto-maintained)

```markdown
# Memory Index

- [prefer_concise_responses](prefer_concise_responses.md) — User prefers terse responses [feedback]
- [auth_compliance](auth_compliance.md) — Auth rewrite driven by legal requirements [project]
```

### SystemPrompt Injection Format

```
## Memories

### prefer_concise_responses [feedback]
Do not add summaries at the end of responses. The user reads diffs directly.

### auth_compliance [project]
Auth rewrite is driven by legal/compliance requirements, not tech debt.
```

---

## 4. Tool Interface

### `save_memory`

**Class**: `SaveMemoryTool`  
**Tool name**: `save_memory`

Input schema:
```json
{
  "name": "string",         
  "description": "string",  
  "type": "string",         
  "content": "string"       
}
```

Behavior: calls `MemoryStore.save(entry)`. If a file with the same name already exists, it is overwritten (upsert). Returns `"Memory saved: {name}"`.

### `delete_memory`

**Class**: `DeleteMemoryTool`  
**Tool name**: `delete_memory`

Input schema:
```json
{
  "name": "string"
}
```

Behavior: calls `MemoryStore.delete(name)`. Returns `"Memory deleted: {name}"` on success, `"Memory not found: {name}"` if the file does not exist (not an error).

---

## 5. `MemoryStore` API

```java
public class MemoryStore {
    public MemoryStore(Path memoryDir) { ... }

    public void save(MemoryEntry entry) throws IOException { ... }
    // Creates memoryDir if absent. Writes frontmatter + content to {name}.md. Calls rebuildIndex().

    public boolean delete(String name) throws IOException { ... }
    // Deletes {name}.md if present. Calls rebuildIndex(). Returns true if deleted, false if not found.

    public List<MemoryEntry> loadAll() throws IOException { ... }
    // Scans *.md files (excluding MEMORY.md). Skips files with unparseable frontmatter.

    private void rebuildIndex() throws IOException { ... }
    // Rewrites MEMORY.md from current loadAll() result.
}
```

Storage path: `{workDir}/.memory/`

---

## 6. QueryEngine Integration

`MemoryStore` is injected as an optional dependency. If `null` or if the directory is empty, the memory section is omitted silently.

```java
private String buildMemorySection() {
    if (memoryStore == null) return "";
    try {
        List<MemoryEntry> entries = memoryStore.loadAll();
        if (entries.isEmpty()) return "";
        // format entries into "## Memories\n..." string
    } catch (IOException e) {
        return ""; // memory load failure must not block the session
    }
}
```

The memory section is appended to the system prompt before the first turn.

---

## 7. Error Handling

| Scenario | Behavior |
|----------|----------|
| `.memory/` directory absent | `save` creates it; `loadAll` returns empty list |
| Malformed frontmatter in a file | Skip that file; continue loading the rest |
| `IOException` on write | Tool returns error text to Agent; no exception propagates |
| `delete` on non-existent name | Returns `"Memory not found: {name}"`; not an error |
| Unknown `type` value | Accepted as-is; no validation (four types are convention, not enforcement) |

---

## 8. Testing Strategy

| Test class | Coverage |
|------------|----------|
| `MemoryStoreTest` | save / delete / loadAll / upsert / rebuildIndex — all against a temp directory |
| `SaveMemoryToolTest` | parameter parsing + delegation to MemoryStore |
| `DeleteMemoryToolTest` | parameter parsing + delegation; not-found case |
| `QueryEngineMemoryIT` | inject a pre-populated `.memory/` dir; assert systemPrompt contains Memory section |

---

## 9. Out of Scope (s09)

- `ReadMemoryTool` — memories are loaded automatically at session start
- Automatic memory extraction from conversation
- Scope separation (private vs. team)
- Memory expiry or TTL
- External storage backends

These are explicitly deferred to later chapters (s10+).
