package org.example.agent.permission;

public record PermissionRule(String tool, PermissionBehavior behavior, String content) {}
