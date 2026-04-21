package org.example.agent.tool;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolResultEnvelopeTest {

    @Test
    void success_sets_ok_true_content_and_no_error() {
        var e = ToolResultEnvelope.success("hello");
        assertTrue(e.ok());
        assertEquals("hello", e.content());
        assertFalse(e.isError());
        assertTrue(e.attachments().isEmpty());
    }

    @Test
    void error_sets_ok_false_content_and_is_error() {
        var e = ToolResultEnvelope.error("boom");
        assertFalse(e.ok());
        assertEquals("boom", e.content());
        assertTrue(e.isError());
        assertTrue(e.attachments().isEmpty());
    }

    @Test
    void success_has_empty_context_modifier() {
        var e = ToolResultEnvelope.success("hello");
        assertTrue(e.contextModifier().isEmpty());
    }

    @Test
    void error_has_empty_context_modifier() {
        var e = ToolResultEnvelope.error("boom");
        assertTrue(e.contextModifier().isEmpty());
    }
}
