package org.example.agent.tool.task;

public class NoSuchTaskException extends RuntimeException {
    public NoSuchTaskException(int id) {
        super("Task #" + id + " not found");
    }
}
