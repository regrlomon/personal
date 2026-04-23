package org.example.agent.engine;

import org.example.agent.core.MemoryEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class MemoryStore {

    private final Path memoryDir;

    public MemoryStore(Path memoryDir) {
        this.memoryDir = memoryDir;
    }

    public void save(MemoryEntry entry) throws IOException {
        Files.createDirectories(memoryDir);
        var frontmatter = "---\n" +
                "name: " + entry.name() + "\n" +
                "description: " + entry.description() + "\n" +
                "type: " + entry.type() + "\n" +
                "---\n";
        Files.writeString(memoryDir.resolve(entry.name() + ".md"), frontmatter + entry.content());
        rebuildIndex();
    }

    public boolean delete(String name) throws IOException {
        var file = memoryDir.resolve(name + ".md");
        if (!Files.exists(file)) return false;
        Files.delete(file);
        rebuildIndex();
        return true;
    }

    public List<MemoryEntry> loadAll() throws IOException {
        if (!Files.exists(memoryDir)) return List.of();
        List<MemoryEntry> entries = new ArrayList<>();
        try (var stream = Files.list(memoryDir)) {
            var files = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("MEMORY.md"))
                    .sorted()
                    .toList();
            for (var file : files) {
                var entry = parseFile(file);
                if (entry != null) entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private void rebuildIndex() throws IOException {
        var entries = loadAll();
        var sb = new StringBuilder("# Memory Index\n\n");
        for (var e : entries) {
            sb.append("- [").append(e.name()).append("](").append(e.name()).append(".md) — ")
              .append(e.description()).append(" [").append(e.type()).append("]\n");
        }
        Files.writeString(memoryDir.resolve("MEMORY.md"), sb.toString());
    }

    private static MemoryEntry parseFile(Path file) throws IOException {
        var text = Files.readString(file);
        if (!text.startsWith("---\n")) return null;
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) return null;
        var frontmatter = text.substring(4, end);
        var content = text.substring(end + 5);
        var fields = new HashMap<String, String>();
        for (var line : frontmatter.split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                fields.put(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
        }
        var name = fields.get("name");
        var description = fields.get("description");
        var type = fields.get("type");
        if (name == null || description == null || type == null) return null;
        return new MemoryEntry(name, description, type, content);
    }
}
