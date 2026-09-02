package com.myhomelibcorp.shared.util;

/** Small cross-layer helper for presenting the deepest actionable error without duplicating unwrap loops. */
public final class ThrowableMessages {
    private ThrowableMessages() { }

    public static String rootMessage(Throwable error) {
        return rootMessage(error, error == null ? "Unknown error" : error.getClass().getSimpleName());
    }

    public static String rootMessage(Throwable error, String fallback) {
        if (error == null) return fallback;
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }
}
