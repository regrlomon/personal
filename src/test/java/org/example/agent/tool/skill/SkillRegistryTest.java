package org.example.agent.tool.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    @TempDir Path skillsDir;

    @Test
    void loads_skill_body_from_SKILL_md() throws IOException {
        writeSkillFile("code-review", "Code review checklist", "Step 1: Check logic.");
        var registry = SkillRegistry.of(skillsDir);
        assertEquals("Step 1: Check logic.", registry.loadBody("code-review"));
    }

    @Test
    void describe_available_lists_all_skills() throws IOException {
        writeSkillFile("alpha", "Alpha skill", "alpha body");
        writeSkillFile("beta", "Beta skill", "beta body");
        var registry = SkillRegistry.of(skillsDir);
        var desc = registry.describeAvailable();
        assertTrue(desc.startsWith("Skills available:"), "should start with header");
        assertTrue(desc.contains("- alpha: Alpha skill"), "should list alpha");
        assertTrue(desc.contains("- beta: Beta skill"), "should list beta");
    }

    @Test
    void empty_registry_returns_empty_description() {
        assertEquals("", SkillRegistry.empty().describeAvailable());
    }

    @Test
    void load_unknown_skill_throws_illegal_argument() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> SkillRegistry.empty().loadBody("no-such-skill"));
        assertTrue(ex.getMessage().contains("no-such-skill"));
    }

    private void writeSkillFile(String name, String description, String body) throws IOException {
        var dir = skillsDir.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: " + description + "\n---\n" + body);
    }
}
