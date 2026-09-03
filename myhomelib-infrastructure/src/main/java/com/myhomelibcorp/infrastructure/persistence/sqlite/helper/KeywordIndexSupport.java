package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import org.springframework.jdbc.core.JdbcTemplate;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Maintains the normalized keyword projection used by navigation and exact keyword filters.
 * Raw {@code books.keywords} is intentionally retained for import/export compatibility.
 */
public final class KeywordIndexSupport {
    private static final int SQLITE_IN_CHUNK = 400;

    private KeywordIndexSupport() {}

    public record KeywordToken(String normalizedName, String displayName) {}

    /** Parses comma/semicolon/pipe separated metadata and de-duplicates it case-insensitively. */
    public static List<KeywordToken> tokenize(String rawKeywords) {
        if (rawKeywords == null || rawKeywords.isBlank()) return List.of();
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String raw : rawKeywords.split("[,;|]")) {
            String display = normalizeDisplay(raw);
            if (display.isEmpty()) continue;
            String normalized = normalizeKeyword(display);
            if (!normalized.isEmpty()) unique.putIfAbsent(normalized, display);
        }
        return unique.entrySet().stream()
                .map(entry -> new KeywordToken(entry.getKey(), entry.getValue()))
                .toList();
    }

    public static String normalizeKeyword(String value) {
        if (value == null || value.isBlank()) return "";
        return normalizeDisplay(value).toLowerCase(Locale.ROOT);
    }

    private static String normalizeDisplay(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
        return normalized.replaceAll("[\\s\\u00A0]+", " ");
    }

    public static void replaceForBook(JdbcTemplate jdbc, String bookId, String rawKeywords) {
        if (jdbc == null || bookId == null || bookId.isBlank()) return;
        replaceForBooks(jdbc, Map.of(bookId, rawKeywords == null ? "" : rawKeywords));
    }

    /**
     * Replaces keyword links for a bounded batch of books. The operation is idempotent and is
     * expected to participate in the caller's current transaction where one exists.
     */
    public static void replaceForBooks(JdbcTemplate jdbc, Map<String, String> keywordsByBookId) {
        if (jdbc == null || keywordsByBookId == null || keywordsByBookId.isEmpty()) return;

        List<String> bookIds = keywordsByBookId.keySet().stream()
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (bookIds.isEmpty()) return;

        deleteLinks(jdbc, bookIds);

        LinkedHashMap<String, String> keywordLabels = new LinkedHashMap<>();
        List<Object[]> links = new ArrayList<>();
        for (String bookId : bookIds) {
            LinkedHashSet<String> seenForBook = new LinkedHashSet<>();
            for (KeywordToken token : tokenize(keywordsByBookId.get(bookId))) {
                keywordLabels.putIfAbsent(token.normalizedName(), token.displayName());
                if (seenForBook.add(token.normalizedName())) {
                    links.add(new Object[]{token.normalizedName(), bookId});
                }
            }
        }

        if (!keywordLabels.isEmpty()) {
            List<Object[]> keywordRows = keywordLabels.entrySet().stream()
                    .map(entry -> new Object[]{entry.getKey(), entry.getValue()})
                    .toList();
            jdbc.batchUpdate("""
                    INSERT INTO keywords(normalized_name, display_name)
                    VALUES (?, ?)
                    ON CONFLICT(normalized_name) DO UPDATE SET
                        display_name = CASE
                            WHEN TRIM(COALESCE(keywords.display_name, '')) = '' THEN excluded.display_name
                            ELSE keywords.display_name
                        END
                    """, keywordRows);
        }
        if (!links.isEmpty()) {
            jdbc.batchUpdate("INSERT OR IGNORE INTO keyword_books(normalized_name, book_id) VALUES (?, ?)", links);
        }
    }

    public static int removeOrphanKeywords(JdbcTemplate jdbc) {
        if (jdbc == null) return 0;
        return jdbc.update("""
                DELETE FROM keywords
                WHERE NOT EXISTS (
                    SELECT 1 FROM keyword_books kb WHERE kb.normalized_name = keywords.normalized_name
                )
                """);
    }

    private static void deleteLinks(JdbcTemplate jdbc, List<String> bookIds) {
        for (int from = 0; from < bookIds.size(); from += SQLITE_IN_CHUNK) {
            List<String> part = bookIds.subList(from, Math.min(bookIds.size(), from + SQLITE_IN_CHUNK));
            String placeholders = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            jdbc.update("DELETE FROM keyword_books WHERE book_id IN (" + placeholders + ")", part.toArray());
        }
    }
}
