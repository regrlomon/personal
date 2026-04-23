package org.example.agent.engine;

import org.example.agent.core.MemoryEntry;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MemoryStoreTest {

    @Test
    void memoryEntry_stores_fields() {
        var entry = new MemoryEntry("pref_tabs", "Use tabs", "user", "Always use tabs.");
        assertEquals("pref_tabs", entry.name());
        assertEquals("Use tabs", entry.description());
        assertEquals("user", entry.type());
        assertEquals("Always use tabs.", entry.content());
    }
}
