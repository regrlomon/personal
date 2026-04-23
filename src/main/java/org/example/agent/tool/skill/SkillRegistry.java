package org.example.agent.tool.skill;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class SkillRegistry {

    private final Map<String, SkillDocument> skills;

    public SkillRegistry(Map<String, SkillDocument> skills) {
        this.skills = Map.copyOf(skills);
    }

    public static SkillRegistry of(Path skillsDir) {
        var skills = new LinkedHashMap<String, SkillDocument>();
        try (var stream = Files.walk(skillsDir, 2)) {
            stream.filter(p -> p.getFileName().toString().equals("SKILL.md"))
                  .forEach(path -> {
                      try {
                          var doc = parse(Files.readString(path));
                          skills.put(doc.manifest().name(), doc);
                      } catch (IOException e) {
                          throw new UncheckedIOException(e);
                      }
                  });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new SkillRegistry(skills);
    }

    public static SkillRegistry empty() {
        return new SkillRegistry(Map.of());
    }

    public String describeAvailable() {
        if (skills.isEmpty()) return "";
        var sb = new StringBuilder("Skills available:");
        skills.values().forEach(doc ->
                sb.append("\n- ").append(doc.manifest().name())
                  .append(": ").append(doc.manifest().description()));
        return sb.toString();
    }

    public String loadBody(String name) {
        var doc = skills.get(name);
        if (doc == null) throw new IllegalArgumentException("unknown skill: " + name);
        return doc.body();
    }

    private static SkillDocument parse(String raw) {
        var text = raw.replace("\r\n", "\n");
        if (!text.startsWith("---\n")) {
            throw new IllegalArgumentException("SKILL.md must start with '---'");
        }
        var parts = text.split("---\n", 3);
        if (parts.length < 3) {
            throw new IllegalArgumentException("SKILL.md frontmatter not closed");
        }
        String name = null, description = "";
        for (var line : parts[1].lines().toList()) {
            var idx = line.indexOf(':');
            if (idx < 0) continue;
            var key = line.substring(0, idx).trim();
            var value = line.substring(idx + 1).trim();
            if ("name".equals(key)) name = value;
            else if ("description".equals(key)) description = value;
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("SKILL.md missing 'name' in frontmatter");
        }
        return new SkillDocument(new SkillManifest(name, description), parts[2].strip());
    }
}
