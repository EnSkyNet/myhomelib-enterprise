package com.myhomelibcorp.plugin.api;

/** Non-throwing result of a plugin call; ordinary plugin failures are quarantined by the host. */
public record PluginInvocationResult<R>(Outcome outcome, R value, String message) {
    public enum Outcome { SUCCESS, BLOCKED, FAILED }

    public PluginInvocationResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        message = message == null ? "" : message;
    }

    public static <R> PluginInvocationResult<R> success(R value) {
        return new PluginInvocationResult<>(Outcome.SUCCESS, value, "");
    }

    public static <R> PluginInvocationResult<R> blocked(String message) {
        return new PluginInvocationResult<>(Outcome.BLOCKED, null, message);
    }

    public static <R> PluginInvocationResult<R> failed(String message) {
        return new PluginInvocationResult<>(Outcome.FAILED, null, message);
    }
}
