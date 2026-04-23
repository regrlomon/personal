package org.example.agent.tool;

import org.example.agent.core.Message;
import org.example.agent.core.ToolDefinition;
import org.example.agent.engine.ContextCompactor;
import org.example.agent.tool.todo.PlanningState;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class CompactTool implements Tool {

    private final ContextCompactor compactor;
    private final Supplier<List<Message>> messagesReader;
    private final Consumer<List<Message>> messagesWriter;
    private final Supplier<PlanningState> planReader;

    public CompactTool(ContextCompactor compactor,
                       Supplier<List<Message>> messagesReader,
                       Consumer<List<Message>> messagesWriter,
                       Supplier<PlanningState> planReader) {
        this.compactor = compactor;
        this.messagesReader = messagesReader;
        this.messagesWriter = messagesWriter;
        this.planReader = planReader;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "compact",
                "Compact the conversation history to free up context space.",
                Map.of()
        );
    }

    @Override
    public ToolResultEnvelope execute(Map<String, Object> input, ToolUseContext ctx) {
        var compacted = compactor.fullCompact(messagesReader.get(), planReader.get());
        messagesWriter.accept(compacted);
        return ToolResultEnvelope.success("Context compacted successfully.");
    }

    @Override
    public boolean isConcurrencySafe() {
        return false;
    }
}
