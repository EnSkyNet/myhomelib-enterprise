package com.myhomelibcorp.application.metadata;

import java.time.Duration;

/** Checked provider failure with generic, vendor-neutral classification. */
public class MetadataProviderException extends Exception {
    private final MetadataProviderErrorKind kind;
    private final Duration retryAfter;

    public MetadataProviderException(MetadataProviderErrorKind kind, String message) {
        this(kind, message, null, Duration.ZERO);
    }

    public MetadataProviderException(MetadataProviderErrorKind kind, String message, Throwable cause) {
        this(kind, message, cause, Duration.ZERO);
    }

    public MetadataProviderException(
            MetadataProviderErrorKind kind,
            String message,
            Throwable cause,
            Duration retryAfter) {
        super(cleanMessage(message), cause);
        this.kind = kind == null ? MetadataProviderErrorKind.FAILED : kind;
        this.retryAfter = normalizeRetryAfter(retryAfter);
    }

    public MetadataProviderErrorKind kind() {
        return kind;
    }

    /** Generic backoff hint for coordinator/adapters; vendor quota details never become part of the UI model. */
    public Duration retryAfter() {
        return retryAfter;
    }

    public static MetadataProviderException cancelled() {
        return new MetadataProviderException(MetadataProviderErrorKind.CANCELLED, "Metadata lookup cancelled");
    }

    public static MetadataProviderException timeout() {
        return new MetadataProviderException(MetadataProviderErrorKind.TIMEOUT, "Metadata provider timed out");
    }

    public static MetadataProviderException rateLimited(Duration retryAfter) {
        return new MetadataProviderException(
                MetadataProviderErrorKind.RATE_LIMITED,
                "Metadata provider rate limit reached",
                null,
                retryAfter);
    }

    private static Duration normalizeRetryAfter(Duration value) {
        if (value == null || value.isNegative()) return Duration.ZERO;
        return value;
    }

    private static String cleanMessage(String message) {
        return message == null || message.isBlank() ? "Metadata provider failed" : message.trim();
    }
}
