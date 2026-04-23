// src/test/java/org/example/agent/hook/ShellHookRunnerTest.java
package org.example.agent.hook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShellHookRunnerTest {

    private static ShellHookRunner runnerWith(HookEventName event, List<String> cmds) {
        return new ShellHookRunner(HookConfig.fromMap(Map.of(event, cmds)), 5);
    }

    @Test
    void exit_0_returns_ok() {
        var runner = runnerWith(HookEventName.PRE_TOOL_USE, List.of("exit 0"));
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(0, result.exitCode());
    }

    @Test
    void exit_1_returns_block_with_stdout_as_message() {
        var runner = runnerWith(HookEventName.PRE_TOOL_USE,
                List.of("echo 'not allowed'; exit 1"));
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(1, result.exitCode());
        assertEquals("not allowed", result.message());
    }

    @Test
    void exit_2_returns_inject_with_stdout_as_message() {
        var runner = runnerWith(HookEventName.POST_TOOL_USE,
                List.of("echo 'audit logged'; exit 2"));
        var result = runner.run(new HookEvent(HookEventName.POST_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(2, result.exitCode());
        assertEquals("audit logged", result.message());
    }

    @Test
    void first_blocking_command_short_circuits() {
        var runner = runnerWith(HookEventName.PRE_TOOL_USE,
                List.of("echo 'blocked'; exit 1", "echo 'should not run'; exit 0"));
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(1, result.exitCode());
        assertEquals("blocked", result.message());
    }

    @Test
    void multiple_ok_commands_all_run_and_return_ok() {
        var runner = runnerWith(HookEventName.PRE_TOOL_USE,
                List.of("exit 0", "exit 0"));
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(0, result.exitCode());
    }

    @Test
    void timeout_returns_ok_and_does_not_hang() {
        var runner1s = new ShellHookRunner(
                HookConfig.fromMap(Map.of(HookEventName.PRE_TOOL_USE, List.of("sleep 5"))), 1);
        var result = runner1s.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of("tool_name", "bash")));
        assertEquals(0, result.exitCode(), "timed-out hook should return ok");
    }

    @Test
    void no_commands_for_event_returns_ok() {
        var runner = new ShellHookRunner(HookConfig.empty(), 5);
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE, Map.of()));
        assertEquals(0, result.exitCode());
    }

    @Test
    void payload_is_sent_as_json_to_stdin() {
        var runner = runnerWith(HookEventName.PRE_TOOL_USE,
                List.of("read -r line; echo \"got:$line\"; exit 2"));
        var result = runner.run(new HookEvent(HookEventName.PRE_TOOL_USE,
                Map.of("tool_name", "bash")));
        assertEquals(2, result.exitCode());
        assertTrue(result.message().startsWith("got:"), "Expected stdin JSON in output, got: " + result.message());
        assertTrue(result.message().contains("tool_name"), "Expected tool_name in JSON payload");
    }

    @Test
    void toJson_serializes_string_values() {
        var json = ShellHookRunner.toJson(Map.of("key", "value with \"quotes\""));
        assertEquals("{\"key\":\"value with \\\"quotes\\\"\"}", json);
    }
}
