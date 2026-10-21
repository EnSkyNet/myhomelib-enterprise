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

    /**
     * Tests a cause chain without retaining visited nodes, including a complete cycle if present.
     * The predicate should be side-effect-free because a cyclic node may be checked twice.
     */
    public static boolean anyCause(Throwable error, java.util.function.Predicate<? super Throwable> predicate) {
        java.util.Objects.requireNonNull(predicate, "predicate");
        Throwable current = error;
        Throwable ahead = error;
        while (current != null) {
            if (predicate.test(current)) return true;
            current = current.getCause();
            if (ahead != null) {
                ahead = ahead.getCause();
                if (ahead != null) ahead = ahead.getCause();
            }
            if (current != null && current == ahead) {
                // Floyd's meeting point is not necessarily the first node in the cycle.
                // Locate the entry and inspect a full cycle so no matching cause is missed.
                Throwable entry = error;
                Throwable meeting = current;
                while (entry != meeting) {
                    entry = entry.getCause();
                    meeting = meeting.getCause();
                }
                do {
                    if (predicate.test(meeting)) return true;
                    meeting = meeting.getCause();
                } while (meeting != entry);
                return false;
            }
        }
        return false;
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
