package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

public class EditFileTool implements Tool {

    private final PathSandbox sandbox;

    public EditFileTool(PathSandbox sandbox) {
        this.sandbox = sandbox;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "edit_file",
                "Replace old_text with new_text in a file. Fails if old_text is not found.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "path", Map.of("type", "string", "description", "Relative path to file"),
                                "old_text", Map.of("type", "string", "description", "Text to replace"),
                                "new_text", Map.of("type", "string", "description", "Replacement text")
                        ),
                        "required", java.util.List.of("path", "old_text", "new_text")
                )
        );
    }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        try {
            var path = sandbox.resolve((String) input.get("path"));
            var oldText = (String) input.get("old_text");
            var newText = (String) input.get("new_text");
            var content = Files.readString(path);
            if (!content.contains(oldText)) {
                return ToolResultEnvelope.error("Error: old_text not found in " + path.getFileName());
            }
            Files.writeString(path, content.replace(oldText, newText));
            return ToolResultEnvelope.success("OK: edited " + path.getFileName());
        } catch (SecurityException e) {
            return ToolResultEnvelope.error("Error: " + e.getMessage());
        } catch (IOException e) {
            return ToolResultEnvelope.error("Error: cannot edit file: " + e.getMessage());
        }
    }
}
