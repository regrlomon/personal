package org.example.agent.hook;

import java.util.Map;

public record HookEvent(HookEventName name, Map<String, Object> payload) {}
