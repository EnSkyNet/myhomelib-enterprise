package com.myhomelibcorp.application.usecase.conversion;

/**
 * Provider-neutral, user-safe classification of a conversion failure.
 *
 * <p>The original cause is retained for logs/support bundles, while UI code can report the
 * stage/provider without exposing local paths, command lines or third-party diagnostics.</p>
 */
public final class BookConversionException extends Exception {
    public enum Stage {
        SOURCE_READ,
        PROVIDER,
        OUTPUT_VALIDATION,
        PUBLICATION,
        CATALOG_REGISTRATION
    }

    private final Stage stage;
    private final String providerId;
    private final String sourceFormat;
    private final String targetFormat;

    public BookConversionException(Stage stage,
                                   String providerId,
                                   String sourceFormat,
                                   String targetFormat,
                                   Throwable cause) {
        super(safeMessage(stage, providerId, sourceFormat, targetFormat), cause);
        this.stage = stage == null ? Stage.PROVIDER : stage;
        this.providerId = safe(providerId);
        this.sourceFormat = safe(sourceFormat);
        this.targetFormat = safe(targetFormat);
    }

    public Stage stage() { return stage; }
    public String providerId() { return providerId; }
    public String sourceFormat() { return sourceFormat; }
    public String targetFormat() { return targetFormat; }

    private static String safeMessage(Stage stage, String providerId, String sourceFormat, String targetFormat) {
        return "Book conversion failed at " + (stage == null ? Stage.PROVIDER : stage)
                + " [provider=" + safe(providerId)
                + ", source=" + safe(sourceFormat)
                + ", target=" + safe(targetFormat) + "]";
    }

    private static String safe(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= 80 ? clean : clean.substring(0, 80);
    }
}
