package org.example.agent.tool.task;

import java.util.List;

public record UpdateResult(TaskRecord task, List<Integer> unblocked) {}
