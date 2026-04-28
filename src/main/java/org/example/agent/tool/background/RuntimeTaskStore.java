package org.example.agent.tool.background;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class RuntimeTaskStore {

    private final Path dir;

    RuntimeTaskStore(Path dir) {
        this.dir = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void save(RuntimeTaskRecord record) {
        var path = dir.resolve(record.id() + ".json");
        try {
            Files.writeString(path, serialize(record));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    RuntimeTaskRecord load(String id) {
        var path = dir.resolve(id + ".json");
        if (!Files.exists(path)) return null;
        try {
            return deserialize(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<RuntimeTaskRecord> loadAll() {
        try {
            if (!Files.exists(dir)) return List.of();
            try (var stream = Files.list(dir)) {
                return stream
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.endsWith(".json"))
                        .map(name -> load(name.replace(".json", "")))
                        .filter(r -> r != null)
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // package-private for tests
    static String serialize(RuntimeTaskRecord r) {
        return "{\n" +
                "  \"id\": " + quote(r.id()) + ",\n" +
                "  \"description\": " + quote(r.description()) + ",\n" +
                "  \"status\": \"" + r.status().name().toLowerCase() + "\",\n" +
                "  \"startedAt\": " + r.startedAt() + ",\n" +
                "  \"resultPreview\": " + quote(r.resultPreview()) + ",\n" +
                "  \"outputFile\": " + quote(r.outputFile().toString()) + "\n" +
                "}";
    }

    // package-private for tests
    static RuntimeTaskRecord deserialize(String json) {
        String id            = parseStringField(json, "id");
        String description   = parseStringField(json, "description");
        RuntimeTaskStatus status = RuntimeTaskStatus.valueOf(
                parseStringField(json, "status").toUpperCase());
        long startedAt       = parseLongField(json, "startedAt");
        String resultPreview = parseStringField(json, "resultPreview");
        Path outputFile      = Paths.get(parseStringField(json, "outputFile"));
        return new RuntimeTaskRecord(id, description, status, startedAt, resultPreview, outputFile);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String parseStringField(String json, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + field);
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static long parseLongField(String json, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + field);
        return Long.parseLong(m.group(1));
    }
}
