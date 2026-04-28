package org.example.agent.tool.task;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

class TaskStore {

    private final Path tasksDir;

    TaskStore(Path tasksDir) {
        this.tasksDir = tasksDir;
        try {
            Files.createDirectories(tasksDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    TaskRecord load(int id) {
        var path = tasksDir.resolve("task_" + id + ".json");
        if (!Files.exists(path)) throw new NoSuchTaskException(id);
        try {
            return deserialize(Files.readString(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void save(TaskRecord record) {
        var path = tasksDir.resolve("task_" + record.id() + ".json");
        try {
            Files.writeString(path, serialize(record));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<TaskRecord> loadAll() {
        try {
            if (!Files.exists(tasksDir)) return List.of();
            try (var stream = Files.list(tasksDir)) {
                return stream
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.matches("task_\\d+\\.json"))
                        .map(name -> load(Integer.parseInt(
                                name.replace("task_", "").replace(".json", ""))))
                        .sorted(Comparator.comparingInt(TaskRecord::id))
                        .collect(Collectors.toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int nextId() {
        try {
            if (!Files.exists(tasksDir)) return 1;
            try (var stream = Files.list(tasksDir)) {
                return stream
                        .map(p -> p.getFileName().toString())
                        .filter(name -> name.matches("task_\\d+\\.json"))
                        .mapToInt(name -> Integer.parseInt(
                                name.replace("task_", "").replace(".json", "")))
                        .max()
                        .orElse(0) + 1;
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // package-private for tests
    static String serialize(TaskRecord t) {
        return "{\n" +
                "  \"id\": " + t.id() + ",\n" +
                "  \"subject\": " + quote(t.subject()) + ",\n" +
                "  \"description\": " + quote(t.description()) + ",\n" +
                "  \"status\": \"" + t.status().name().toLowerCase() + "\",\n" +
                "  \"blockedBy\": " + intArray(t.blockedBy()) + ",\n" +
                "  \"blocks\": " + intArray(t.blocks()) + ",\n" +
                "  \"owner\": " + quote(t.owner()) + "\n" +
                "}";
    }

    // package-private for tests
    static TaskRecord deserialize(String json) {
        int id              = parseIntField(json, "id");
        String subject      = parseStringField(json, "subject");
        String description  = parseStringField(json, "description");
        TaskStatus status   = TaskStatus.valueOf(parseStringField(json, "status").toUpperCase());
        List<Integer> blockedBy = parseIntArray(json, "blockedBy");
        List<Integer> blocks    = parseIntArray(json, "blocks");
        String owner        = parseStringField(json, "owner");
        return new TaskRecord(id, subject, description, status, blockedBy, blocks, owner);
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String intArray(List<Integer> list) {
        if (list.isEmpty()) return "[]";
        return "[" + list.stream().map(String::valueOf).collect(Collectors.joining(", ")) + "]";
    }

    private static int parseIntField(String json, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + field);
        return Integer.parseInt(m.group(1));
    }

    private static String parseStringField(String json, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + field);
        return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static List<Integer> parseIntArray(String json, String field) {
        var m = Pattern.compile("\"" + field + "\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(json);
        if (!m.find()) throw new IllegalArgumentException("Missing field: " + field);
        String content = m.group(1).trim();
        if (content.isEmpty()) return new ArrayList<>();
        return Arrays.stream(content.split(","))
                .map(String::trim)
                .map(Integer::parseInt)
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
