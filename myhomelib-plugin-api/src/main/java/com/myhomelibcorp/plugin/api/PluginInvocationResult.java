package com.myhomelibcorp.plugin.api;

/**
 * Non-throwing host result of a managed plugin call.
 *
 * <p>{@link Outcome#OPERATION_ERROR} represents a normal checked failure of one operation
 * (for example an unreadable book or a temporary I/O problem). It does not quarantine the
 * plugin. {@link Outcome#FAILED} is reserved for plugin-code/runtime failures that caused
 * quarantine.</p>
 */
public record PluginInvocationResult<R>(Outcome outcome, R value, String message, Throwable cause) {
    public enum Outcome { SUCCESS, BLOCKED, OPERATION_ERROR, FAILED }

    public PluginInvocationResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        message = message == null ? "" : message;
        if (outcome == Outcome.SUCCESS && cause != null) {
            throw new IllegalArgumentException("successful result cannot have a cause");
        }
    }

    public static <R> PluginInvocationResult<R> success(R value) {
        return new PluginInvocationResult<>(Outcome.SUCCESS, value, "", null);
    }

    public static <R> PluginInvocationResult<R> blocked(String message) {
        return new PluginInvocationResult<>(Outcome.BLOCKED, null, message, null);
    }

    public static <R> PluginInvocationResult<R> operationError(Throwable failure) {
        if (failure == null) throw new IllegalArgumentException("failure is required");
        return new PluginInvocationResult<>(Outcome.OPERATION_ERROR, null, summarize(failure), failure);
    }

    public static <R> PluginInvocationResult<R> failed(String message) {
        return new PluginInvocationResult<>(Outcome.FAILED, null, message, null);
    }

    public static <R> PluginInvocationResult<R> failed(Throwable failure) {
        if (failure == null) throw new IllegalArgumentException("failure is required");
        return new PluginInvocationResult<>(Outcome.FAILED, null, summarize(failure), failure);
    }

    private static String summarize(Throwable failure) {
        String type = failure.getClass().getSimpleName();
        String text = failure.getMessage();
        return text == null || text.isBlank() ? type : type + ": " + text;
    }
}
