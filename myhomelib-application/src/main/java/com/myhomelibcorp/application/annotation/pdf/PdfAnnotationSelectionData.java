package com.myhomelibcorp.application.annotation.pdf;

import java.util.Objects;

/**
 * Application-facing contract for a future PDF text-layer annotation selection.
 * Offsets are deliberately page-local: callers must not reinterpret them as global Reader offsets.
 */
public record PdfAnnotationSelectionData(
        String bookId,
        String artifactId,
        int pageIndex,
        int pageTextStartOffset,
        int pageTextEndOffset,
        String quote,
        String prefix,
        String suffix
) {
    public PdfAnnotationSelectionData {
        bookId = required(bookId, "bookId");
        artifactId = normalized(artifactId);
        if (pageIndex < 0) throw new IllegalArgumentException("pageIndex must be >= 0");
        if (pageTextStartOffset < 0) throw new IllegalArgumentException("pageTextStartOffset must be >= 0");
        if (pageTextEndOffset < pageTextStartOffset) {
            throw new IllegalArgumentException("pageTextEndOffset must be >= pageTextStartOffset");
        }
        quote = Objects.requireNonNullElse(quote, "");
        prefix = Objects.requireNonNullElse(prefix, "");
        suffix = Objects.requireNonNullElse(suffix, "");
    }

    public boolean hasTextRange() {
        return pageTextEndOffset > pageTextStartOffset && !quote.isBlank();
    }

    private static String required(String value, String field) {
        String normalized = normalized(value);
        if (normalized == null) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String normalized(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
