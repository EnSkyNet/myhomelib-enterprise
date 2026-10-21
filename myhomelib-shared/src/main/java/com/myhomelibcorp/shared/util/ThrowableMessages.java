package com.myhomelibcorp.shared.util;

/** Small cross-layer helper for presenting the deepest actionable error without duplicating unwrap loops. */
public final class ThrowableMessages {
    private ThrowableMessages() { }

    public static String rootMessage(Throwable error) {
        return rootMessage(error, error == null ? "Unknown error" : error.getClass().getSimpleName());
    }

    public static String rootMessage(Throwable error, String fallback) {
        if (error == null) return fallback;
        Throwable current = deepest(error);
        String message = current.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    /** Cycle-safe O(1)-space cause traversal; custom Throwables can form cause cycles. */
    static Throwable deepest(Throwable error) {
        Throwable current = error;
        Throwable ahead = error;
        while (current != null) {
            Throwable cause = current.getCause();
            if (cause == null || cause == current) return current;
            if (ahead != null) {
                ahead = ahead.getCause();
                if (ahead != null) ahead = ahead.getCause();
            }
            if (cause == ahead) return current;
            current = cause;
        }
        return null;
    }
}
