package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

public class ReadFileTool implements org.example.agent.tool.Tool {

    private static final int MAX_CHARS = 50_000;

    private final PathSandbox sandbox;

    public ReadFileTool(PathSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "read_file",
                "Read a file from the workspace. Optionally limit the number of lines returned.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Relative path to file"),
                                "limit", Map.of("type", "integer", "description", "Max lines to return")
                        ),
                        "required", java.util.List.of("path")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> input) {
        try {
            var path = sandbox.resolve((String) input.get("path"));
            var text = Files.readString(path);
            var lines = text.split("\n", -1);
            Object limitObj = input.get("limit");
            if (limitObj != null) {
                int limit = ((Number) limitObj).intValue();
                if (limit < lines.length) {
                    text = String.join("\n", java.util.Arrays.copyOf(lines, limit));
                }
            }
            return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error: cannot read file: " + e.getMessage();
        }
    }
}
