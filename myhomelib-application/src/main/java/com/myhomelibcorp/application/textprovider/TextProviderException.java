package com.myhomelibcorp.application.textprovider;

import java.time.Duration;

/** Checked provider failure that does not leak vendor response payloads to UI code. */
public class TextProviderException extends Exception {
    private final TextProviderErrorKind kind;
    private final Duration retryAfter;

    public TextProviderException(TextProviderErrorKind kind, String message) {
        this(kind, message, null, Duration.ZERO);
    }

    public TextProviderException(TextProviderErrorKind kind, String message, Throwable cause) {
        this(kind, message, cause, Duration.ZERO);
    }

    public TextProviderException(
            TextProviderErrorKind kind,
            String message,
            Throwable cause,
            Duration retryAfter) {
        super(cleanMessage(message), cause);
        this.kind = kind == null ? TextProviderErrorKind.FAILED : kind;
        this.retryAfter = normalizeRetryAfter(retryAfter);
    }

    public TextProviderErrorKind kind() {
        return kind;
    }

    public Duration retryAfter() {
        return retryAfter;
    }

    public static TextProviderException cancelled() {
        return new TextProviderException(TextProviderErrorKind.CANCELLED, "Text provider request cancelled");
    }

    public static TextProviderException timeout() {
        return new TextProviderException(TextProviderErrorKind.TIMEOUT, "Text provider request timed out");
    }

    public static TextProviderException rateLimited(Duration retryAfter) {
        return new TextProviderException(
                TextProviderErrorKind.RATE_LIMITED,
                "Text provider rate limit reached",
                null,
                retryAfter);
    }

    private static Duration normalizeRetryAfter(Duration value) {
        if (value == null || value.isNegative()) return Duration.ZERO;
        return value;
    }

    private static String cleanMessage(String message) {
        return message == null || message.isBlank() ? "Text provider request failed" : message.trim();
    }
}
