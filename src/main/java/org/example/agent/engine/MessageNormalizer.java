package org.example.agent.engine;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.Message;
import org.example.agent.core.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class MessageNormalizer {

    public static List<Message> normalize(List<Message> messages) {
        var filled = fillOrphanedToolUse(messages);
        return mergeConsecutiveSameRole(filled);
    }

    private static List<Message> fillOrphanedToolUse(List<Message> messages) {
        Set<String> existingResultIds = messages.stream()
                .flatMap(m -> m.content().stream())
                .filter(b -> b instanceof ContentBlock.ToolResult)
                .map(b -> ((ContentBlock.ToolResult) b).toolUseId())
                .collect(Collectors.toSet());

        var result = new ArrayList<>(messages);
        for (var msg : messages) {
            if (msg.role() != Role.ASSISTANT) continue;
            for (var block : msg.content()) {
                if (block instanceof ContentBlock.ToolUse tu && !existingResultIds.contains(tu.id())) {
                    result.add(new Message(Role.USER,
                            List.of(new ContentBlock.ToolResult(tu.id(), "(cancelled)"))));
                }
            }
        }
        return result;
    }

    private static List<Message> mergeConsecutiveSameRole(List<Message> messages) {
        if (messages.isEmpty()) return List.of();
        var merged = new ArrayList<Message>();
        merged.add(messages.get(0));
        for (int i = 1; i < messages.size(); i++) {
            var current = messages.get(i);
            var last = merged.get(merged.size() - 1);
            if (current.role() == last.role()) {
                var combined = new ArrayList<>(last.content());
                combined.addAll(current.content());
                merged.set(merged.size() - 1, new Message(last.role(), combined));
            } else {
                merged.add(current);
            }
        }
        return merged;
    }
}
