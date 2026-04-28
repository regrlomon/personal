# S13 Background Tasks — Design Spec

**日期**: 2026-04-28  
**章节**: s13（接续 s12 任务系统）  
**范围**: 后台执行槽位 + 通知路径，与 s12 工作目标完全分离

---

## 核心原则

- 主循环仍只有一条，并行的是等待，不是主循环本身
- 后台任务（执行槽位）≠ s12 任务（工作目标）
- 通知负责提醒，文件负责存原文
- BackgroundManager 注入方式与 s12 TaskManager 对称

---

## 数据结构

### RuntimeTaskStatus

```java
enum RuntimeTaskStatus {
    RUNNING, COMPLETED, FAILED, TIMEOUT, CANCELLED
}
```

### RuntimeTaskRecord

```java
record RuntimeTaskRecord(
    String id,              // UUID 短前缀，如 "a1b2c3d4"
    String description,     // 任务描述（shell 命令或 callable 名称）
    RuntimeTaskStatus status,
    long startedAt,         // epoch millis
    String resultPreview,   // 完成后的 500 字摘要
    Path outputFile         // .runtime-tasks/{id}.log
)
```

### BackgroundNotification

```java
record BackgroundNotification(
    String taskId,
    String description,     // 任务描述（用于通知展示）
    RuntimeTaskStatus status,
    String preview
)
```

### 磁盘布局

```
.runtime-tasks/
  {id}.json    ← RuntimeTaskRecord（状态，JSON 持久化）
  {id}.log     ← 完整输出
```

与 s12 的 `.tasks/task_{id}.json` 平行，语义不同：
- `.tasks/` = 工作目标（s12）
- `.runtime-tasks/` = 执行槽位（s13）

---

## 核心架构

### BackgroundTask 接口

```java
interface BackgroundTask {
    String describe();
    void execute(Path outputFile) throws Exception;

    default String preview(Path outputFile) throws IOException {
        // 默认读前 500 字节
        var bytes = Files.readAllBytes(outputFile);
        var full = new String(bytes, StandardCharsets.UTF_8);
        return full.length() <= 500 ? full : full.substring(0, 500) + "...";
    }
}
```

两个内置实现：

**ShellBackgroundTask**
- 包装 `ProcessBuilder`
- stdout + stderr 重定向到 `outputFile`
- 支持 `timeoutSeconds`，超时抛 `TimeoutException` → 状态 TIMEOUT

**CallableBackgroundTask**
- 包装 `Callable<String>`
- 返回值写入 `outputFile`
- 供 Java 内部代码直接调用，不通过模型工具暴露

### BackgroundManager

```java
class BackgroundManager {
    private final ExecutorService executor;              // newCachedThreadPool
    private final Map<String, Future<?>> futures;        // 用于 cancel
    private final RuntimeTaskStore store;                // JSON 持久化
    private final List<BackgroundNotification> notificationQueue;
    private final ReentrantLock lock;

    // 提交后台任务，立即返回 task_id
    String submit(BackgroundTask task);

    // 查询单个任务
    RuntimeTaskRecord check(String id);

    // 列出所有任务
    List<RuntimeTaskRecord> list();

    // 取消运行中的任务，Future.cancel(true)
    boolean cancel(String id);

    // 清空并返回通知队列（QueryEngine 每轮调用）
    List<BackgroundNotification> drain();

    // 关闭线程池（run() 结束时调用）
    void shutdown();
}
```

**submit() 内部流程**：
```
1. 生成 id，创建 RuntimeTaskRecord(RUNNING)，写 JSON
2. executor.submit(() -> {
       task.execute(outputFile)
       preview = task.preview(outputFile)
       lock {
           record → COMPLETED/FAILED/TIMEOUT
           notificationQueue.add(notification)
           store.save(record)
       }
   })
3. futures.put(id, future)
4. 立即返回 id
```

### RuntimeTaskStore

与 s12 TaskStore 对称，手工 JSON 序列化：
- `save(RuntimeTaskRecord)` → `{id}.json`
- `load(String id)` → `RuntimeTaskRecord`
- `loadAll()` → `List<RuntimeTaskRecord>`

### ToolUseContext 扩展

新增字段与 wither 方法（与 taskManager 完全对称）：

```java
private final BackgroundManager backgroundManager;

public BackgroundManager backgroundManager() { return backgroundManager; }

public ToolUseContext withBackgroundManager(BackgroundManager manager) {
    return new ToolUseContext(
        permissionContext, mcpClients, appState,
        notifications, cwd, planningState,
        taskManager,
        manager,            // ← 新增
        permissionChecker, userConfirmation, hookRunner
    );
}
```

---

## 4 个工具

### background_run

```
名称：background_run
描述：在后台启动 shell 命令，立即返回 task_id

参数：
  command  string  必需  Shell 命令（如 "pytest -v"）
  timeout  int     可选  超时秒数，默认 300

返回：
  "Started background task [a1b2c3]: pytest -v"
```

内部：创建 `ShellBackgroundTask`，调用 `manager.submit()`。

### background_check

```
名称：background_check
描述：查询单个后台任务的状态

参数：
  id  string  必需

返回（RUNNING）：
  "[a1b2c3] RUNNING - started 12s ago: pytest -v"

返回（COMPLETED）：
  "[a1b2c3] COMPLETED - pytest -v
  Preview: 5 passed in 3.2s
  Full output: .runtime-tasks/a1b2c3.log"
```

### background_list

```
名称：background_list
描述：列出所有后台任务
参数：无

返回示例：
  [a1b2c3] RUNNING   pytest -v         (12s)
  [d4e5f6] COMPLETED npm install       (45s) ✓
  [g7h8i9] FAILED    docker build .    (8s)  ✗
```

### background_cancel

```
名称：background_cancel
描述：取消运行中的后台任务

参数：
  id  string  必需

行为：Future.cancel(true)，状态更新为 CANCELLED，写通知队列

返回：
  "[a1b2c3] cancelled"
  "[a1b2c3] not cancellable (already COMPLETED)"
```

---

## QueryEngine 通知注入

### 主循环新增步骤

```
while true:
  1. ← 新增：drainAndInject(state, ctx)
  2. 调用模型
  3. 工具执行（同步）
  4. 如果调用了 background_run → submit() 立即返回 task_id
  5. 继续下一轮
```

### 注入格式

通知作为 `user` 角色消息注入 messages：

```
[bg:a1b2c3] COMPLETED - pytest -v
Preview: 5 passed in 3.2s
Full output: .runtime-tasks/a1b2c3.log

[bg:d4e5f6] FAILED - docker build .
Preview: ERROR: failed to solve...
Full output: .runtime-tasks/d4e5f6.log
```

### QueryEngine 改动点（最小化）

```java
// run() 初始化
ctx = ctx.withBackgroundManager(
    new BackgroundManager(Paths.get(cwd, ".runtime-tasks"))
);

// 主循环顶部（私有方法）
private void drainAndInject(QueryState state, ToolUseContext ctx) {
    var notifications = ctx.backgroundManager().drain();
    if (notifications.isEmpty()) return;
    var text = notifications.stream()
        .map(n -> "[bg:%s] %s - %s\nPreview: %s\nFull output: .runtime-tasks/%s.log"
            .formatted(n.taskId(), n.status(), n.description(), n.preview(), n.taskId()))
        .collect(joining("\n\n"));
    state.messages().add(Message.user(text));
}

// run() 结束前
ctx.backgroundManager().shutdown();
```

---

## 包结构

```
org.example.agent.tool.background/
  BackgroundTask.java
  ShellBackgroundTask.java
  CallableBackgroundTask.java
  BackgroundManager.java
  RuntimeTaskRecord.java
  RuntimeTaskStatus.java
  BackgroundNotification.java
  RuntimeTaskStore.java
  BackgroundRunTool.java
  BackgroundCheckTool.java
  BackgroundListTool.java
  BackgroundCancelTool.java
```

---

## 不在范围内

- 线程池大小配置（当前 newCachedThreadPool，生产可后补）
- 任务优先级
- 跨进程重启恢复执行中任务（RUNNING 任务重启后标为 FAILED）
- 通过工具暴露 CallableBackgroundTask（仅内部 Java 代码使用）
