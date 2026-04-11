package org.example.agent.tool;

public class UnknownToolException extends RuntimeException {
    public UnknownToolException(String toolName) {
        super("Unknown tool: " + toolName);
    }
}
