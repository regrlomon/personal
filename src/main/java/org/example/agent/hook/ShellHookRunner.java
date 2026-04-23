package org.example.agent.hook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ShellHookRunner implements HookRunner {

    private final HookConfig config;
    private final int timeoutSeconds;

    public ShellHookRunner(HookConfig config) {
        this(config, 10);
    }

    ShellHookRunner(HookConfig config, int timeoutSeconds) {
        this.config = config;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public HookResult run(HookEvent event) {
        List<String> commands = config.commandsFor(event.name());
        if (commands.isEmpty()) return HookResult.ok();
        String payloadJson = toJson(event.payload());
        for (String command : commands) {
            var result = runCommand(command, payloadJson);
            if (result.exitCode() == 1 || result.exitCode() == 2) return result;
        }
        return HookResult.ok();
    }

    private HookResult runCommand(String command, String stdinJson) {
        try {
            var process = new ProcessBuilder("bash", "-c", command).start();

            // Read stdout in background before writing stdin (avoids pipe deadlock)
            var stdoutFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });

            // Write payload JSON to stdin, then close
            try (var out = process.getOutputStream()) {
                out.write(stdinJson.getBytes(StandardCharsets.UTF_8));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutFuture.cancel(true);
                System.err.println("[HookRunner] timed out: " + command);
                return HookResult.ok();
            }

            String stdout = stdoutFuture.join().strip();
            return switch (process.exitValue()) {
                case 1  -> HookResult.block(stdout);
                case 2  -> HookResult.inject(stdout);
                default -> HookResult.ok();
            };
        } catch (Exception e) {
            System.err.println("[HookRunner] hook error: " + e.getMessage());
            return HookResult.ok();
        }
    }

    // package-private for test
    static String toJson(Map<String, Object> map) {
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append('"').append(e.getKey()).append("\":").append(valueToJson(e.getValue()));
        }
        return sb.append("}").toString();
    }

    @SuppressWarnings("unchecked")
    private static String valueToJson(Object v) {
        if (v == null) return "null";
        if (v instanceof String s)
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (v instanceof Map<?, ?> m) return toJson((Map<String, Object>) m);
        return String.valueOf(v);  // Number, Boolean — List not supported (payload values are Strings and Maps)
    }
}
