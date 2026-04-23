package org.example.agent.hook;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HookResultTest {

    @Test
    void ok_has_exit_code_0_and_empty_message() {
        var r = HookResult.ok();
        assertEquals(0, r.exitCode());
        assertEquals("", r.message());
    }

    @Test
    void block_has_exit_code_1() {
        var r = HookResult.block("not allowed");
        assertEquals(1, r.exitCode());
        assertEquals("not allowed", r.message());
    }

    @Test
    void inject_has_exit_code_2() {
        var r = HookResult.inject("extra context");
        assertEquals(2, r.exitCode());
        assertEquals("extra context", r.message());
    }
}
