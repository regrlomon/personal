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

    private static String[] shellPrefix() {
        var os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // prefer Git bash on Windows; fall back to cmd
            for (var p : new String[]{
                    "D:\\Git\\bin\\bash.exe",
                    "C:\\Program Files\\Git\\bin\\bash.exe"}) {
                if (new java.io.File(p).isFile()) return new String[]{p, "-c"};
            }
            return new String[]{"cmd", "/c"};
        }
        return new String[]{"bash", "-c"};
    }

    private static void destroyTree(Process process) {
        var os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            // On Windows, destroyForcibly() only kills the shell process;
            // child processes (e.g. sleep.exe spawned by bash) remain alive
            // and keep the output file locked. Use taskkill /T to kill the tree.
            try {
                long pid = process.pid();
                Process killer = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid))
                        .redirectErrorStream(true)
                        .start();
                // Wait for taskkill to complete, clearing interrupt flag temporarily
                boolean wasInterrupted = Thread.interrupted();
                try {
                    killer.waitFor(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    wasInterrupted = true;
                } finally {
                    if (wasInterrupted) Thread.currentThread().interrupt();
                }
            } catch (Exception ignored) {}
        }
        process.destroyForcibly();
    }

    @Override
    public void execute(Path outputFile) throws Exception {
        var prefix = shellPrefix();
        var cmd = new java.util.ArrayList<String>();
        cmd.addAll(java.util.Arrays.asList(prefix));
        cmd.add(command);
        var process = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(outputFile.toFile())
                .start();
        try {
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                destroyTree(process);
                throw new TimeoutException("Command timed out after " + timeoutSeconds + "s");
            }
        } catch (InterruptedException e) {
            destroyTree(process);
            // Wait for process to truly exit and release file handles.
            long deadline = System.currentTimeMillis() + 3000;
            while (process.isAlive() && System.currentTimeMillis() < deadline) {
                try { process.waitFor(200, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}
            }
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
