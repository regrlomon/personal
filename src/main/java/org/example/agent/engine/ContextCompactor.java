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
        // placeholder — implemented in Task 3
        return List.copyOf(messages);
    }

    public List<Message> fullCompact(List<Message> messages, PlanningState plan) {
        // placeholder — implemented in Task 4
        return List.of(Message.user("This conversation was compacted for continuity."));
    }
}
