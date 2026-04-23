package org.example.agent.hook;

public record HookResult(int exitCode, String message) {
    public static HookResult ok()                   { return new HookResult(0, ""); }
    public static HookResult block(String message)  { return new HookResult(1, message); }
    public static HookResult inject(String message) { return new HookResult(2, message); }
}
