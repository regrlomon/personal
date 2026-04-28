package org.example.agent.tool.background;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ShellBackgroundTask implements BackgroundTask {

    private final String command;
    private final int timeoutSeconds;

    public ShellBackgroundTask(String command, int timeoutSeconds) {
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String describe() { return command; }

    @Override
    public void execute(Path outputFile) throws Exception {
        var process = new ProcessBuilder("bash", "-c", command)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new TimeoutException("Command timed out after " + timeoutSeconds + "s");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
