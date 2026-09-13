package com.myhomelibcorp.application.content;

/** Typed outcome: unsupported/cancelled inputs are normal control flow rather than parser exceptions. */
public record ContentExtractionResult(
        ContentExtractionStatus status,
        ExtractedContent content,
        String extractorId,
        String message
) {
    public ContentExtractionResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        extractorId = extractorId == null ? "" : extractorId.trim();
        message = message == null ? "" : message.trim();
        if (status == ContentExtractionStatus.SUCCESS && content == null) {
            throw new IllegalArgumentException("successful extraction requires content");
        }
        if (status != ContentExtractionStatus.SUCCESS && content != null) {
            throw new IllegalArgumentException("non-successful extraction cannot carry content");
        }
    }

    public static ContentExtractionResult success(String extractorId, ExtractedContent content) {
        return new ContentExtractionResult(ContentExtractionStatus.SUCCESS, content, extractorId, "");
    }

    public static ContentExtractionResult unsupported(String format) {
        return new ContentExtractionResult(ContentExtractionStatus.UNSUPPORTED, null, "",
                "Unsupported content format: " + (format == null || format.isBlank() ? "unknown" : format));
    }

    public static ContentExtractionResult cancelled(String extractorId) {
        return new ContentExtractionResult(ContentExtractionStatus.CANCELLED, null, extractorId, "Content extraction cancelled");
    }

    public static ContentExtractionResult failed(String extractorId, String message) {
        return new ContentExtractionResult(ContentExtractionStatus.FAILED, null, extractorId,
                message == null || message.isBlank() ? "Content extraction failed" : message);
    }

    public boolean isSuccess() {
        return status == ContentExtractionStatus.SUCCESS;
    }
}
