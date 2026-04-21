package org.example.agent.tool.todo;

import java.util.List;
import java.util.Objects;

public class PlanningState {

    private List<PlanItem> items = List.of();
    private int roundsSinceUpdate = 0;

    public List<PlanItem> items() { return items; }

    public int roundsSinceUpdate() { return roundsSinceUpdate; }

    public void update(List<PlanItem> newItems) {
        Objects.requireNonNull(newItems, "items must not be null");
        long inProgressCount = newItems.stream()
                .filter(i -> i.status() == PlanStatus.IN_PROGRESS)
                .count();
        if (inProgressCount > 1) {
            throw new IllegalArgumentException("Only one item can be in_progress at a time");
        }
        this.items = List.copyOf(newItems);
        this.roundsSinceUpdate = 0;
    }

    public void tickRound() {
        roundsSinceUpdate++;
    }

    public boolean needsReminder() {
        return roundsSinceUpdate >= 3;
    }

    public String render() {
        var sb = new StringBuilder();
        for (var item : items) {
            String marker = switch (item.status()) {
                case PENDING -> "[ ]";
                case IN_PROGRESS -> "[>]";
                case COMPLETED -> "[x]";
            };
            sb.append(marker).append(" ").append(item.content()).append("\n");
        }
        return sb.toString().stripTrailing();
    }
}
