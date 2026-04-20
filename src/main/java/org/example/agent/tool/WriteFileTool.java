package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

public class WriteFileTool implements org.example.agent.tool.Tool {

    private final PathSandbox sandbox;

    public WriteFileTool(PathSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "write_file",
                "Write content to a file in the workspace, creating or overwriting it.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Relative path to file"),
                                "content", Map.of("type", "string", "description", "Content to write")
                        ),
                        "required", java.util.List.of("path", "content")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> input) {
        try {
            var path = sandbox.resolve((String) input.get("path"));
            Files.createDirectories(path.getParent());
            Files.writeString(path, (String) input.get("content"));
            return "OK: wrote " + path.getFileName();
        } catch (SecurityException e) {
            return "Error: " + e.getMessage();
        } catch (IOException e) {
            return "Error: cannot write file: " + e.getMessage();
        }
    }
}
