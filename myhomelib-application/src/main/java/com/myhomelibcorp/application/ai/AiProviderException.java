package com.myhomelibcorp.application.ai;

/** Checked provider failure with a bounded, secret-free message contract. */
public final class AiProviderException extends Exception {
    private final AiProviderErrorKind kind;

    public AiProviderException(AiProviderErrorKind kind, String message) {
        this(kind, message, null);
    }

    public AiProviderException(AiProviderErrorKind kind, String message, Throwable cause) {
        super(clean(message), cause);
        this.kind = kind == null ? AiProviderErrorKind.FAILED : kind;
    }

    public AiProviderErrorKind kind() {
        return kind;
    }

    public static AiProviderException cancelled() {
        return new AiProviderException(AiProviderErrorKind.CANCELLED, "AI request cancelled");
    }

    public static AiProviderException timeout() {
        return new AiProviderException(AiProviderErrorKind.TIMEOUT, "AI request timed out");
    }

    public static AiProviderException consent(String message) {
        return new AiProviderException(AiProviderErrorKind.CONSENT_REQUIRED, message);
    }

    private static String clean(String message) {
        if (message == null || message.isBlank()) return "AI provider request failed";
        String value = message.replaceAll("[\\r\\n\\t]+", " ").trim();
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
