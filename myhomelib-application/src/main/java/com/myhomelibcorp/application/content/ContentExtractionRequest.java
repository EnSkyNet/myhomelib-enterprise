package com.myhomelibcorp.application.content;

import com.myhomelibcorp.shared.format.SupportedFormatRegistry;

import java.util.Locale;
import java.util.Objects;

/** Immutable request for one book-content extraction operation. */
public record ContentExtractionRequest(ContentExtractionSource source, String format) {
    public ContentExtractionRequest {
        source = Objects.requireNonNull(source, "source");
        format = normalizeFormat(format, source.name());
    }

    public static ContentExtractionRequest of(ContentExtractionSource source) {
        return new ContentExtractionRequest(source, null);
    }

    private static String normalizeFormat(String value, String fileName) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.isBlank()) return normalized;
        return SupportedFormatRegistry.standard().detect(fileName)
                .map(format -> format.id().toLowerCase(Locale.ROOT))
                .orElse("");
    }
}
