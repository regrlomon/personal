package org.example.agent.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HookConfigTest {

    @Test
    void load_from_file_parses_commands(@TempDir Path dir) throws Exception {
        var json = """
                {
                  "SessionStart": ["./scripts/start.sh"],
                  "PreToolUse": ["./guard.sh", "./log.sh"],
                  "PostToolUse": ["./post.sh"]
                }
                """;
        var file = dir.resolve("hooks.json");
        Files.writeString(file, json);

        var config = HookConfig.load(file);

        assertEquals(List.of("./scripts/start.sh"), config.commandsFor(HookEventName.SESSION_START));
        assertEquals(List.of("./guard.sh", "./log.sh"), config.commandsFor(HookEventName.PRE_TOOL_USE));
        assertEquals(List.of("./post.sh"), config.commandsFor(HookEventName.POST_TOOL_USE));
    }

    @Test
    void unknown_event_names_are_ignored(@TempDir Path dir) throws Exception {
        var json = """
                {
                  "UnknownEvent": ["./whatever.sh"],
                  "PreToolUse": ["./guard.sh"]
                }
                """;
        var file = dir.resolve("hooks.json");
        Files.writeString(file, json);

        var config = HookConfig.load(file);

        assertEquals(List.of("./guard.sh"), config.commandsFor(HookEventName.PRE_TOOL_USE));
        assertTrue(config.commandsFor(HookEventName.SESSION_START).isEmpty());
    }

    @Test
    void empty_returns_no_commands() {
        var config = HookConfig.empty();
        assertTrue(config.commandsFor(HookEventName.PRE_TOOL_USE).isEmpty());
        assertTrue(config.commandsFor(HookEventName.SESSION_START).isEmpty());
        assertTrue(config.commandsFor(HookEventName.POST_TOOL_USE).isEmpty());
    }

    @Test
    void load_from_classpath_finds_test_hooks_json() {
        // loads src/test/resources/hooks.json (test classpath takes priority over main)
        var config = HookConfig.loadFromClasspath();
        assertEquals(List.of("exit 0"), config.commandsFor(HookEventName.PRE_TOOL_USE));
    }
}
