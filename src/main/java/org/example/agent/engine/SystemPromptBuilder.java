package org.example.agent.engine;

import org.example.agent.tool.skill.SkillRegistry;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

public class SystemPromptBuilder {

    private final SkillRegistry skillRegistry;
    private final MemoryStore memoryStore;
    private final String cwd;

    public SystemPromptBuilder(SkillRegistry skillRegistry, MemoryStore memoryStore, String cwd) {
        this.skillRegistry = skillRegistry;
        this.memoryStore = memoryStore;
        this.cwd = cwd;
    }

    public String build(String core) {
        return List.of(
                buildCore(core),
                buildTools(),
                buildSkills(),
                buildMemory(),
                buildPeragentMd(),
                buildDynamic()
        ).stream()
         .filter(s -> !s.isBlank())
         .collect(Collectors.joining("\n\n"));
    }

    String buildCore(String core) {
        return core != null ? core : "";
    }

    String buildTools() {
        return "";
    }

    String buildSkills() {
        if (skillRegistry == null) return "";
        return skillRegistry.describeAvailable();
    }

    String buildMemory() {
        if (memoryStore == null) return "";
        try {
            var entries = memoryStore.loadAll();
            if (entries.isEmpty()) return "";
            var sb = new StringBuilder("## Memories\n");
            for (var e : entries) {
                sb.append("\n### ").append(e.name()).append(" [").append(e.type()).append("]\n");
                sb.append(e.content()).append("\n");
            }
            return sb.toString().stripTrailing();
        } catch (java.io.IOException e) {
            return "";
        }
    }

    String buildPeragentMd() {
        return "";
    }

    String buildDynamic() {
        return "=== Dynamic Context ===\n" +
               "Date: " + LocalDate.now() + "\n" +
               "CWD:  " + cwd;
    }
}
