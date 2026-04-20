package org.example.agent.tool;

import java.nio.file.Path;

public class PathSandbox {

    private final Path workdir;

    public PathSandbox(Path workdir) {
        this.workdir = workdir.toAbsolutePath().normalize();
    }

    public Path resolve(String input) {
        var resolved = workdir.resolve(input).normalize();
        if (!resolved.startsWith(workdir)) {
            throw new SecurityException("Path escapes workspace: " + input);
        }
        return resolved;
    }
}
