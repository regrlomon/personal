package org.example.agent.tool.background;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

public class CallableBackgroundTask implements BackgroundTask {

    private final String description;
    private final Callable<String> callable;

    public CallableBackgroundTask(String description, Callable<String> callable) {
        this.description = description;
        this.callable = callable;
    }

    @Override
    public String describe() { return description; }

    @Override
    public void execute(Path outputFile) throws Exception {
        var result = callable.call();
        Files.writeString(outputFile, result != null ? result : "", StandardCharsets.UTF_8);
    }
}
