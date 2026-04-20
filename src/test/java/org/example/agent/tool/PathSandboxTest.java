package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PathSandboxTest {

    @TempDir
    Path workdir;

    @Test
    void resolves_path_inside_workdir() {
        var sandbox = new PathSandbox(workdir);
        var result = sandbox.resolve("README.md");
        assertEquals(workdir.resolve("README.md").normalize(), result);
    }

    @Test
    void resolves_nested_path_inside_workdir() {
        var sandbox = new PathSandbox(workdir);
        var result = sandbox.resolve("src/main/Foo.java");
        assertEquals(workdir.resolve("src/main/Foo.java").normalize(), result);
    }

    @Test
    void throws_on_absolute_path_outside_workdir() {
        var sandbox = new PathSandbox(workdir);
        assertThrows(SecurityException.class, () -> sandbox.resolve("/etc/passwd"));
    }

    @Test
    void throws_on_traversal_escape() {
        var sandbox = new PathSandbox(workdir);
        assertThrows(SecurityException.class, () -> sandbox.resolve("../../etc/passwd"));
    }

    @Test
    void throws_on_traversal_that_looks_safe_but_escapes() {
        var sandbox = new PathSandbox(workdir);
        assertThrows(SecurityException.class, () -> sandbox.resolve("subdir/../../../etc/passwd"));
    }
}
