package org.example.agent.hook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

public class HookConfig {

    private final Map<HookEventName, List<String>> commands;

    private HookConfig(Map<HookEventName, List<String>> commands) {
        this.commands = commands;
    }

    public List<String> commandsFor(HookEventName event) {
        return commands.getOrDefault(event, List.of());
    }

    public static HookConfig load(Path path) throws IOException {
        return parse(Files.readString(path));
    }

    public static HookConfig loadFromClasspath() {
        try (InputStream is = HookConfig.class.getClassLoader().getResourceAsStream("hooks.json")) {
            if (is == null) return empty();
            return parse(new String(is.readAllBytes()));
        } catch (Exception e) {
            System.err.println("[HookConfig] failed to load hooks.json from classpath: " + e.getMessage());
            return empty();
        }
    }

    public static HookConfig empty() {
        return new HookConfig(Map.of());
    }

    // package-private, for tests
    static HookConfig fromMap(Map<HookEventName, List<String>> map) {
        return new HookConfig(Collections.unmodifiableMap(new EnumMap<>(map)));
    }

    private static HookConfig parse(String json) {
        Map<HookEventName, List<String>> result = new EnumMap<>(HookEventName.class);
        var entryPat = Pattern.compile("\"(\\w+)\"\\s*:\\s*\\[([^\\]]*)]");
        var strPat   = Pattern.compile("\"([^\"]*)\"");
        var m = entryPat.matcher(json);
        while (m.find()) {
            var event = parseEventName(m.group(1));
            if (event == null) continue;
            var cmds = new ArrayList<String>();
            var sm = strPat.matcher(m.group(2));
            while (sm.find()) cmds.add(sm.group(1));
            result.put(event, List.copyOf(cmds));
        }
        return new HookConfig(Collections.unmodifiableMap(result));
    }

    private static HookEventName parseEventName(String key) {
        return switch (key) {
            case "SessionStart" -> HookEventName.SESSION_START;
            case "PreToolUse"   -> HookEventName.PRE_TOOL_USE;
            case "PostToolUse"  -> HookEventName.POST_TOOL_USE;
            default             -> null;
        };
    }
}
