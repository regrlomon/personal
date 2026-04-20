package org.example.agent.tool;

import org.example.agent.core.ToolDefinition;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class BashTool implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "bash",
                "Execute a shell command and return combined stdout and stderr.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "command", Map.of("type", "string", "description", "Shell command to run")
                        ),
                        "required", List.of("command")
                )
        );
    }

    @Override
    public String execute(Map<String, Object> input) {
        try {
            var process = new ProcessBuilder("bash", "-c", (String) input.get("command"))
                    .redirectErrorStream(true)
                    .start();
            var output = new String(process.getInputStream().readAllBytes());
            process.waitFor();
            return output;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Error: " + e.getMessage();
        }
    }
}
