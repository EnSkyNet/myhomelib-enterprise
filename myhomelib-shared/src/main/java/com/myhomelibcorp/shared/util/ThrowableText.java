package com.myhomelibcorp.shared.util;

/** Small exception-text helpers shared by non-adjacent layers without duplicating traversal logic. */
public final class ThrowableText {
    private ThrowableText() { }

    public static String rootMessage(Throwable error, String unknownFallback) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) current = current.getCause();
        if (current == null) return unknownFallback == null ? "" : unknownFallback;
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
