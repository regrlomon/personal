package org.example.agent.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptBuilderTest {

    @TempDir
    Path tempDir;

    private SystemPromptBuilder builder() {
        return new SystemPromptBuilder(null, null, tempDir.toString());
    }

    @Test
    void buildCore_returns_core_unchanged() {
        assertEquals("hello", builder().buildCore("hello"));
    }

    @Test
    void buildCore_returns_empty_for_null() {
        assertEquals("", builder().buildCore(null));
    }

    @Test
    void buildTools_returns_empty() {
        assertEquals("", builder().buildTools());
    }

    @Test
    void buildDynamic_contains_today_and_cwd() {
        var dynamic = builder().buildDynamic();
        assertTrue(dynamic.startsWith("=== Dynamic Context ==="),
                "must start with header");
        assertTrue(dynamic.contains("Date: " + LocalDate.now()),
                "must contain today's date");
        assertTrue(dynamic.contains("CWD:  " + tempDir),
                "must contain cwd");
    }
}
