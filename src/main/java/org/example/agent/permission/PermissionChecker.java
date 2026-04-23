package org.example.agent.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class PermissionChecker {

    private static final Set<String> WRITE_TOOLS = Set.of("bash", "write_file", "edit_file");
    private static final Set<String> READ_ONLY_TOOLS = Set.of("read_file");
    private static final List<String> BASH_DANGER_PATTERNS = List.of(
            "sudo", "rm -rf", "$(", "`", "| sh", "| bash"
    );

    private PermissionMode mode;
    private final List<PermissionRule> denyRules  = new ArrayList<>();
    private final List<PermissionRule> allowRules = new ArrayList<>();
    private int denialCount = 0;

    public PermissionChecker(PermissionMode mode) {
        this.mode = mode;
    }

    public PermissionChecker addDenyRule(PermissionRule rule) {
        denyRules.add(rule);
        return this;
    }

    public PermissionChecker addAllowRule(PermissionRule rule) {
        allowRules.add(rule);
        return this;
    }

    public PermissionDecision check(String toolName, Map<String, Object> input) {
        // Step 1: explicit deny rules
        for (var rule : denyRules) {
            if (ruleMatches(rule, toolName, input)) {
                denialCount++;
                return new PermissionDecision(PermissionBehavior.DENY,
                        "matched deny rule" + (rule.content() != null ? ": " + rule.content() : ""));
            }
        }

        // Step 1b: bash safety patterns
        if ("bash".equals(toolName)) {
            var command = (String) input.getOrDefault("command", "");
            for (var pattern : BASH_DANGER_PATTERNS) {
                if (command.contains(pattern)) {
                    denialCount++;
                    return new PermissionDecision(PermissionBehavior.DENY,
                            "dangerous bash pattern detected: " + pattern);
                }
            }
        }

        // Step 2: mode check
        if (mode == PermissionMode.PLAN && WRITE_TOOLS.contains(toolName)) {
            denialCount++;
            return new PermissionDecision(PermissionBehavior.DENY,
                    "plan mode blocks write tool: " + toolName);
        }
        if (mode == PermissionMode.AUTO && READ_ONLY_TOOLS.contains(toolName)) {
            return new PermissionDecision(PermissionBehavior.ALLOW,
                    "auto mode allows read-only tool: " + toolName);
        }

        // Step 3: explicit allow rules
        for (var rule : allowRules) {
            if (ruleMatches(rule, toolName, input)) {
                return new PermissionDecision(PermissionBehavior.ALLOW, "matched allow rule");
            }
        }

        // Step 4: fallback — ask user
        return new PermissionDecision(PermissionBehavior.ASK, "needs user confirmation");
    }

    public int denialCount() { return denialCount; }

    public PermissionMode mode() { return mode; }

    public void setMode(PermissionMode mode) { this.mode = mode; }

    private boolean ruleMatches(PermissionRule rule, String toolName, Map<String, Object> input) {
        if (!rule.tool().equals(toolName)) return false;
        if (rule.content() == null) return true;
        var haystack = extractContent(input);
        return haystack.contains(rule.content().replace("*", "").strip());
    }

    private String extractContent(Map<String, Object> input) {
        return input.values().stream()
                .filter(v -> v instanceof String)
                .map(Object::toString)
                .collect(Collectors.joining(" "));
    }
}
