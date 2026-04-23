package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.example.agent.tool.todo.PlanStatus;
import org.example.agent.tool.todo.PlanningState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ContextCompactor {

    static final int PERSIST_THRESHOLD = 10_000;
    static final int MICRO_KEEP_RECENT = 3;
    static final String PLACEHOLDER = "[Earlier tool result omitted for brevity]";

    private final Path outputDir;

    public ContextCompactor(Path outputDir) {
        this.outputDir = outputDir;
    }

    public String persistIfLarge(String toolUseId, String content) {
        if (content.length() <= PERSIST_THRESHOLD) {
            return content;
        }
        try {
            Files.createDirectories(outputDir);
            var file = outputDir.resolve(toolUseId + ".txt");
            Files.writeString(file, content);
            var preview = content.substring(0, Math.min(2000, content.length()));
            return "<persisted-output>\nFull output saved to: " + file
                    + "\nPreview:\n" + preview + "\n</persisted-output>";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<Message> microCompact(List<Message> messages) {
        var toolResultIndices = new ArrayList<Integer>();
        for (int i = 0; i < messages.size(); i++) {
            var msg = messages.get(i);
            if (msg.role() == Role.USER
                    && msg.content().stream().anyMatch(b -> b instanceof ContentBlock.ToolResult)) {
                toolResultIndices.add(i);
            }
        }

        if (toolResultIndices.size() <= MICRO_KEEP_RECENT) {
            return List.copyOf(messages);
        }

        var toCompact = new HashSet<>(
                toolResultIndices.subList(0, toolResultIndices.size() - MICRO_KEEP_RECENT));

        var result = new ArrayList<Message>(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            if (toCompact.contains(i)) {
                var msg = messages.get(i);
                var newBlocks = msg.content().stream()
                        .map(b -> b instanceof ContentBlock.ToolResult tr
                                ? new ContentBlock.ToolResult(tr.toolUseId(), PLACEHOLDER)
                                : b)
                        .toList();
                result.add(new Message(msg.role(), newBlocks));
            } else {
                result.add(messages.get(i));
            }
        }
        return List.copyOf(result);
    }

    public List<Message> fullCompact(List<Message> messages, PlanningState plan) {
        var sb = new StringBuilder();
        sb.append("This conversation was compacted for continuity.\n\n");
        sb.append("## Compacted Context\n\n");

        // Current Goal: first user text message
        messages.stream()
                .filter(m -> m.role() == Role.USER)
                .findFirst()
                .flatMap(m -> m.content().stream()
                        .filter(b -> b instanceof ContentBlock.Text)
                        .map(b -> ((ContentBlock.Text) b).text())
                        .findFirst())
                .ifPresent(goal -> sb.append("### Current Goal\n").append(goal).append("\n\n"));

        // Completed Actions
        var completed = plan.items().stream()
                .filter(i -> i.status() == PlanStatus.COMPLETED)
                .toList();
        if (!completed.isEmpty()) {
            sb.append("### Completed Actions\n");
            completed.forEach(i -> sb.append("- ").append(i.content()).append("\n"));
            sb.append("\n");
        }

        // Pending Tasks
        var pending = plan.items().stream()
                .filter(i -> i.status() != PlanStatus.COMPLETED)
                .toList();
        if (!pending.isEmpty()) {
            sb.append("### Pending Tasks\n");
            pending.forEach(i -> sb.append("- ").append(i.content()).append("\n"));
            sb.append("\n");
        }

        // Persisted Files
        var persistedPaths = extractPersistedPaths(messages);
        if (!persistedPaths.isEmpty()) {
            sb.append("### Persisted Files\n");
            persistedPaths.forEach(p -> sb.append("- ").append(p).append("\n"));
            sb.append("\n");
        }

        // Recent Assistant Output: last assistant Text block, first 1000 chars
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg.role() == Role.ASSISTANT) {
                msg.content().stream()
                        .filter(b -> b instanceof ContentBlock.Text)
                        .map(b -> ((ContentBlock.Text) b).text())
                        .findFirst()
                        .ifPresent(text -> {
                            var preview = text.length() > 1000 ? text.substring(0, 1000) : text;
                            sb.append("### Recent Assistant Output\n").append(preview);
                        });
                break;
            }
        }

        return List.of(Message.user(sb.toString().stripTrailing()));
    }

    private List<String> extractPersistedPaths(List<Message> messages) {
        var paths = new ArrayList<String>();
        for (var msg : messages) {
            for (var block : msg.content()) {
                String text = switch (block) {
                    case ContentBlock.Text t -> t.text();
                    case ContentBlock.ToolResult tr -> tr.content();
                    default -> null;
                };
                if (text != null && text.contains("<persisted-output>")) {
                    for (var line : text.split("\n")) {
                        if (line.startsWith("Full output saved to: ")) {
                            paths.add(line.substring("Full output saved to: ".length()).trim());
                        }
                    }
                }
            }
        }
        return List.copyOf(paths);
    }
}
