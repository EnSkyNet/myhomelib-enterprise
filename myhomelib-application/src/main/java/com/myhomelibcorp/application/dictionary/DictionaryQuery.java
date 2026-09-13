package com.myhomelibcorp.application.dictionary;

import java.util.Locale;

/** One explicit dictionary lookup initiated by the reader user. */
public record DictionaryQuery(String term, String language, int limit) {
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 50;
    public static final int MAX_TERM_LENGTH = 256;

    public DictionaryQuery {
        term = normalizeTerm(term);
        language = normalizeLanguage(language);
        if (term.isBlank()) throw new IllegalArgumentException("Dictionary term is required");
        if (term.length() > MAX_TERM_LENGTH) {
            throw new IllegalArgumentException("Dictionary term exceeds " + MAX_TERM_LENGTH + " characters");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Dictionary result limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static DictionaryQuery of(String term) {
        return new DictionaryQuery(term, "", DEFAULT_LIMIT);
    }

    public static DictionaryQuery of(String term, String language) {
        return new DictionaryQuery(term, language, DEFAULT_LIMIT);
    }

    private static String normalizeTerm(String value) {
        if (value == null) return "";
        return value.strip().replaceAll("\\s+", " ");
    }

    private static String normalizeLanguage(String value) {
        if (value == null || value.isBlank()) return "";
        return value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }
}
