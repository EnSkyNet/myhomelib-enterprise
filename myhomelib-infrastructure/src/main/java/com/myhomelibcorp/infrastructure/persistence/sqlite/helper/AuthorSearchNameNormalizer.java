package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import java.text.Normalizer;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Canonical Unicode-aware author search key stored in SQLite.
 *
 * <p>SQLite's built-in {@code LOWER()} and {@code NOCASE} only provide reliable
 * case folding for ASCII without the ICU extension. Author names are therefore
 * normalized in Java before persistence and search. NFKC also makes visually
 * equivalent compatibility forms use the same indexed key.</p>
 */
public final class AuthorSearchNameNormalizer {
    private AuthorSearchNameNormalizer() {}

    public static String normalize(String firstName, String middleName, String lastName) {
        return Stream.of(lastName, firstName, middleName)
                .map(AuthorSearchNameNormalizer::normalizePart)
                .filter(value -> !value.isEmpty())
                .reduce((left, right) -> left + " " + right)
                .orElse("");
    }

    /** Normalizes a user-entered author query using the same rules as persisted search_name. */
    public static String normalizeQuery(String query) {
        return normalizePart(query);
    }

    private static String normalizePart(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
        // Names imported from external catalogues may contain tabs/NBSP/repeated spaces.
        // Collapsing Unicode whitespace keeps indexed keys deterministic.
        return normalized.replaceAll("[\\s\\u00A0]+", " ");
    }
}
