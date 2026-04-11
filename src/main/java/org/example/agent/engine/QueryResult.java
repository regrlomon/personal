package org.example.agent.engine;

import org.example.agent.core.Message;

import java.util.List;

public sealed interface QueryResult permits
        QueryResult.Success,
        QueryResult.Failed {

    record Success(List<Message> messages, int totalTurns) implements QueryResult {}

    record Failed(Throwable cause, List<Message> messages) implements QueryResult {}
}
