package org.example.agent.tool.todo;

import java.util.Objects;

public record PlanItem(String content, PlanStatus status, String activeForm) {
    public PlanItem {
        Objects.requireNonNull(content, "content must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(activeForm, "activeForm must not be null");
    }
}
