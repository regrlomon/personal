package org.example.agent.engine;

import org.example.agent.core.*;
import org.example.agent.model.ModelResponse;
import org.example.agent.model.StopReason;
import org.example.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryEngineErrorRecoveryTest {

    private QueryParams params(String msg) {
        return new QueryParams(List.of(Message.user(msg)), "sys", null, null, null);
    }

    private ModelResponse endTurn() {
        return new ModelResponse(
                List.of(new ContentBlock.Text("Done.")), StopReason.END_TURN, 10, 5);
    }

    // ---------------------------------------------------------------
    // 场景 1: transport 抖动 → 重试 → 成功
    // ---------------------------------------------------------------
    @Test
    void transient_exception_triggers_retry_then_succeeds() {
        var callCount = new int[]{0};
        var engine = new QueryEngine(
                request -> {
                    callCount[0]++;
                    if (callCount[0] == 1) throw new RuntimeException("connection reset");
                    return endTurn();
                },
                new ToolRegistry(),
                0L   // 0ms backoff for test speed
        );

        var result = engine.run(params("hi"));

        assertInstanceOf(QueryResult.Success.class, result);
        assertEquals(2, callCount[0]);
    }

    // ---------------------------------------------------------------
    // 场景 2: 预算耗尽 → 向上抛出原始异常
    // ---------------------------------------------------------------
    @Test
    void transport_budget_exhausted_throws_original_exception() {
        var engine = new QueryEngine(
                request -> { throw new RuntimeException("connection timeout"); },
                new ToolRegistry(),
                0L
        );

        var ex = assertThrows(RuntimeException.class, () -> engine.run(params("hi")));
        assertTrue(ex.getMessage().contains("connection timeout"));
    }

    // ---------------------------------------------------------------
    // 场景 3: 不可恢复异常 → 立即抛出，不重试
    // ---------------------------------------------------------------
    @Test
    void non_transient_exception_throws_immediately_without_retry() {
        var callCount = new int[]{0};
        var engine = new QueryEngine(
                request -> {
                    callCount[0]++;
                    throw new RuntimeException("internal server error");
                },
                new ToolRegistry(),
                0L
        );

        assertThrows(RuntimeException.class, () -> engine.run(params("hi")));
        assertEquals(1, callCount[0]); // 只调用一次，不重试
    }

    // ---------------------------------------------------------------
    // 场景 4: 已有续写路径未被破坏（MAX_TOKENS → END_TURN）
    // ---------------------------------------------------------------
    @Test
    void max_tokens_recovery_still_works_after_s11_changes() {
        var responses = new ModelResponse[]{
                new ModelResponse(List.of(new ContentBlock.Text("Part 1...")),
                        StopReason.MAX_TOKENS, 10, 100),
                new ModelResponse(List.of(new ContentBlock.Text("...Part 2.")),
                        StopReason.END_TURN, 10, 20)
        };
        var idx = new int[]{0};

        var engine = new QueryEngine(request -> responses[idx[0]++], new ToolRegistry(), 0L);
        var result = engine.run(params("Write a long essay"));

        assertInstanceOf(QueryResult.Success.class, result);
        assertEquals(2, idx[0]);
    }
}
