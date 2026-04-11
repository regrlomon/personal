package org.example.agent.model;

public interface ModelClient {
    ModelResponse call(ModelRequest request);
}
