package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;
import org.example.agent.tool.todo.PlanItem;
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
        // placeholder — implemented in Task 4
        return List.of(Message.user("This conversation was compacted for continuity."));
    }
}
