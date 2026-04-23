package org.example.agent.tool;

import org.example.agent.core.ContentBlock;
import org.example.agent.core.ToolDefinition;
import org.example.agent.permission.*;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ToolRouterPermissionTest {

    private ToolRegistry registryWithEcho() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("echo", "Echoes input", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("ok:" + input.get("text"));
            }
        });
        return registry;
    }

    @Test
    void no_permission_checker_allows_all_tools() {
        var ctx = ToolUseContext.defaults(".");
        var router = new ToolRouter(registryWithEcho());
        var result = router.route(new ContentBlock.ToolUse("1", "echo", Map.of("text", "hello")), ctx);
        assertEquals("ok:hello", result.content());
    }

    @Test
    void deny_decision_returns_error_envelope_without_executing_tool() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT)
                .addDenyRule(new PermissionRule("echo", PermissionBehavior.DENY, null));
        var ctx = ToolUseContext.defaults(".").withPermissions(checker, UserConfirmation.alwaysAllow());
        var router = new ToolRouter(registryWithEcho());
        var result = router.route(new ContentBlock.ToolUse("2", "echo", Map.of("text", "hello")), ctx);
        assertTrue(result.content().startsWith("Permission denied"));
    }

    @Test
    void ask_decision_with_user_allowing_executes_tool() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        var ctx = ToolUseContext.defaults(".").withPermissions(checker, UserConfirmation.alwaysAllow());
        var router = new ToolRouter(registryWithEcho());
        var result = router.route(new ContentBlock.ToolUse("3", "echo", Map.of("text", "hi")), ctx);
        assertEquals("ok:hi", result.content());
    }

    @Test
    void ask_decision_with_user_denying_returns_error_envelope() {
        var checker = new PermissionChecker(PermissionMode.DEFAULT);
        var ctx = ToolUseContext.defaults(".").withPermissions(checker, UserConfirmation.alwaysDeny());
        var router = new ToolRouter(registryWithEcho());
        var result = router.route(new ContentBlock.ToolUse("4", "echo", Map.of("text", "hi")), ctx);
        assertTrue(result.content().startsWith("Permission denied by user"));
    }

    @Test
    void allow_decision_executes_tool_without_asking_user() {
        var checker = new PermissionChecker(PermissionMode.AUTO)
                .addAllowRule(new PermissionRule("echo", PermissionBehavior.ALLOW, null));
        var ctx = ToolUseContext.defaults(".").withPermissions(checker, UserConfirmation.alwaysDeny());
        var router = new ToolRouter(registryWithEcho());
        var result = router.route(new ContentBlock.ToolUse("5", "echo", Map.of("text", "world")), ctx);
        assertEquals("ok:world", result.content());
    }

    @Test
    void plan_mode_denies_bash_even_if_registered() {
        var registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("bash", "runs bash", Map.of());
            }
            @Override
            public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
                return ToolResultEnvelope.success("executed");
            }
        });
        var checker = new PermissionChecker(PermissionMode.PLAN);
        var ctx = ToolUseContext.defaults(".").withPermissions(checker, UserConfirmation.alwaysDeny());
        var router = new ToolRouter(registry);
        var result = router.route(new ContentBlock.ToolUse("6", "bash", Map.of("command", "echo hi")), ctx);
        assertTrue(result.content().startsWith("Permission denied"));
    }
}
