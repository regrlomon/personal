package org.example.agent.permission;

import java.util.Map;

@FunctionalInterface
public interface UserConfirmation {
    boolean confirm(String toolName, Map<String, Object> input, String reason);

    static UserConfirmation alwaysAllow() { return (t, i, r) -> true; }
    static UserConfirmation alwaysDeny()  { return (t, i, r) -> false; }
}
