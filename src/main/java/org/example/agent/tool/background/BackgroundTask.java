package org.example.agent.tool.background;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public interface BackgroundTask {

    String describe();

    void execute(Path outputFile) throws Exception;

    default String preview(Path outputFile) throws IOException {
        var bytes = Files.readAllBytes(outputFile);
        var full = new String(bytes, StandardCharsets.UTF_8);
        return full.length() <= 500 ? full : full.substring(0, 500) + "...";
    }
}
