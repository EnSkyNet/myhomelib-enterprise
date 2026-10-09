package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.application.conversion.BookConversionCapability;

import java.util.Locale;
import java.util.Objects;

/** Stable identity of a non-global Reader preference override. */
public record ReaderPreferenceScope(ReaderPreferenceScopeType type, String key) {
    public ReaderPreferenceScope {
        type = Objects.requireNonNull(type, "type");
        key = normalize(type, key);
        if (key.isBlank()) throw new IllegalArgumentException("Reader preference scope key cannot be blank");
    }

    public static ReaderPreferenceScope book(String bookId) {
        return new ReaderPreferenceScope(ReaderPreferenceScopeType.BOOK, bookId);
    }

    /** Uses the same case-insensitive trimmed identity as the existing series query boundary. */
    public static ReaderPreferenceScope series(String series) {
        return new ReaderPreferenceScope(ReaderPreferenceScopeType.SERIES, series);
    }

    public static ReaderPreferenceScope format(String format) {
        return new ReaderPreferenceScope(ReaderPreferenceScopeType.FORMAT, format);
    }

    public static ReaderPreferenceScope tryBook(String bookId) {
        return isBlank(bookId) ? null : book(bookId);
    }

    public static ReaderPreferenceScope trySeries(String series) {
        return isBlank(series) ? null : series(series);
    }

    public static ReaderPreferenceScope tryFormat(String format) {
        return BookConversionCapability.normalizeFormat(format).isBlank() ? null : format(format);
    }

    private static String normalize(ReaderPreferenceScopeType type, String value) {
        String raw = Objects.toString(value, "").trim();
        return switch (type) {
            case BOOK -> raw;
            case SERIES -> raw.toLowerCase(Locale.ROOT);
            case FORMAT -> BookConversionCapability.normalizeFormat(raw);
        };
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
