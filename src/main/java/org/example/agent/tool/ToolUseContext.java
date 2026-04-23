package org.example.agent.tool;

import org.example.agent.permission.PermissionChecker;
import org.example.agent.permission.UserConfirmation;
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
    private final PermissionChecker permissionChecker;
    private final UserConfirmation userConfirmation;

    private ToolUseContext(Map<String, Object> permissionContext,
                           Map<String, Object> mcpClients,
                           Map<String, Object> appState,
                           List<String> notifications,
                           String cwd,
                           PlanningState planningState,
                           PermissionChecker permissionChecker,
                           UserConfirmation userConfirmation) {
        this.permissionContext = permissionContext;
        this.mcpClients = mcpClients;
        this.appState = appState;
        this.notifications = notifications;
        this.cwd = cwd;
        this.planningState = planningState;
        this.permissionChecker = permissionChecker;
        this.userConfirmation = userConfirmation;
    }

    public static ToolUseContext defaults(String cwd) {
        Objects.requireNonNull(cwd, "cwd must not be null");
        return new ToolUseContext(Map.of(), Map.of(), Map.of(), List.of(), cwd,
                new PlanningState(), null, null);
    }

    public Map<String, Object> permissionContext() { return permissionContext; }
    public Map<String, Object> mcpClients()        { return mcpClients; }
    public Map<String, Object> appState()          { return appState; }
    public List<String>        notifications()     { return notifications; }
    public String              cwd()               { return cwd; }
    public PlanningState       planningState()     { return planningState; }
    public PermissionChecker   permissionChecker() { return permissionChecker; }
    public UserConfirmation    userConfirmation()  { return userConfirmation; }

    public ToolUseContext withNotifications(List<String> notifications) {
        Objects.requireNonNull(notifications, "notifications must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                List.copyOf(notifications), cwd, planningState, permissionChecker, userConfirmation);
    }

    public ToolUseContext withPermissions(PermissionChecker checker, UserConfirmation confirmation) {
        Objects.requireNonNull(checker, "checker must not be null");
        Objects.requireNonNull(confirmation, "confirmation must not be null");
        return new ToolUseContext(permissionContext, mcpClients, appState,
                notifications, cwd, planningState, checker, confirmation);
    }
}
