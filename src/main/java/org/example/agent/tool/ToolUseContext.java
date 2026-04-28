package org.example.agent.tool;

import org.example.agent.hook.HookRunner;
import org.example.agent.permission.PermissionChecker;
import org.example.agent.permission.UserConfirmation;
import org.example.agent.tool.task.TaskManager;
import org.example.agent.tool.todo.PlanningState;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ToolUseContext {

    private final Map<String, Object> permissionContext;
    private final Map<String, Object> mcpClients;
    private final Map<String, Object> appState;
    private final List<String> notifications;
    private final String cwd;
    private final PlanningState planningState;
    private final TaskManager taskManager;
    private final PermissionChecker permissionChecker;
    private final UserConfirmation userConfirmation;
    private final HookRunner hookRunner;

    private ToolUseContext(Map<String, Object> permissionContext,
                           Map<String, Object> mcpClients,
                           Map<String, Object> appState,
                           List<String> notifications,
                           String cwd,
                           PlanningState planningState,
                           TaskManager taskManager,
                           PermissionChecker permissionChecker,
                           UserConfirmation userConfirmation,
                           HookRunner hookRunner) {
        this.permissionContext = permissionContext;
        this.mcpClients = mcpClients;
        this.appState = appState;
        this.notifications = notifications;
        this.cwd = cwd;
        this.planningState = planningState;
        this.taskManager = taskManager;
        this.permissionChecker = permissionChecker;
        this.userConfirmation = userConfirmation;
        this.hookRunner = hookRunner;
    }

    public static ToolUseContext defaults(String cwd) {
        Objects.requireNonNull(cwd, "cwd must not be null");
        return new ToolUseContext(Map.of(), Map.of(), Map.of(), List.of(), cwd,
                new PlanningState(), null, null, null, null);
    }

    public Map<String, Object> permissionContext() { return permissionContext; }
    public Map<String, Object> mcpClients()        { return mcpClients; }
    public Map<String, Object> appState()          { return appState; }
    public List<String>        notifications()     { return notifications; }
    public String              cwd()               { return cwd; }
    public PlanningState       planningState()     { return planningState; }
    public TaskManager         taskManager()       { return taskManager; }
    public PermissionChecker   permissionChecker() { return permissionChecker; }
    public UserConfirmation    userConfirmation()  { return userConfirmation; }
    public HookRunner          hookRunner()        { return hookRunner; }

    public ToolUseContext withNotifications(List<String> notifications) {
        Objects.requireNonNull(notifications, "notifications must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                List.copyOf(notifications), cwd, planningState, taskManager,
                permissionChecker, userConfirmation, hookRunner);
    }

    public ToolUseContext withTaskManager(TaskManager manager) {
        Objects.requireNonNull(manager, "taskManager must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, manager,
                permissionChecker, userConfirmation, hookRunner);
    }

    public ToolUseContext withPermissions(PermissionChecker checker, UserConfirmation confirmation) {
        Objects.requireNonNull(checker,      "checker must not be null");
        Objects.requireNonNull(confirmation, "confirmation must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, taskManager, checker, confirmation, hookRunner);
    }

    public ToolUseContext withHookRunner(HookRunner runner) {
        Objects.requireNonNull(runner, "hookRunner must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, taskManager,
                permissionChecker, userConfirmation, runner);
    }
}
