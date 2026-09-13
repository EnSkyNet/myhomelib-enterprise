package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.duplicate.FuzzyDuplicateCandidateLookup;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQLite blocking adapter for fuzzy duplicate review. Queries are deliberately bounded and use
 * existing book title / author indexes before the scorer performs more expensive similarity work.
 */
@Component
@RequiredArgsConstructor
public class SqliteFuzzyDuplicateCandidateLookup implements FuzzyDuplicateCandidateLookup {
    private static final int MIN_PREFIX = 4;
    private static final int MAX_PREFIX = 18;

    private final CollectionManager collectionManager;

    private JdbcTemplate jdbc() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public List<BookId> findCandidateIds(Book source, int limit) {
        if (source == null || source.getId() == null || limit <= 0) return List.of();
        int bounded = Math.max(1, Math.min(200, limit));
        Set<BookId> ids = new LinkedHashSet<>(bounded);

        String isbn = source.getIsbn() == null ? "" : source.getIsbn().toString().trim();
        if (!isbn.isBlank()) {
            add(ids, jdbc().queryForList("""
                    SELECT id FROM books
                    WHERE id <> ? AND deleted = 0 AND isbn = ?
                    ORDER BY id LIMIT ?
                    """, String.class, source.getId().asString(), isbn, bounded), bounded);
        }
        if (ids.size() >= bounded) return List.copyOf(ids);

        String title = safe(source.getTitle());
        String lastName = source.getAuthors().stream().findFirst().map(Author::getLastName).map(SqliteFuzzyDuplicateCandidateLookup::safe).orElse("");
        if (!title.isBlank()) {
            int remaining = bounded - ids.size();
            if (!lastName.isBlank()) {
                add(ids, jdbc().queryForList("""
                        SELECT DISTINCT b.id
                        FROM books b
                        JOIN book_authors ba ON ba.book_id = b.id
                        JOIN authors a ON a.id = ba.author_id
                        WHERE b.id <> ? AND b.deleted = 0
                          AND lower(b.title) = lower(?)
                          AND lower(a.last_name) = lower(?)
                        ORDER BY b.id LIMIT ?
                        """, String.class, source.getId().asString(), title, lastName, bounded), bounded);
            }
            if (ids.size() >= bounded) return List.copyOf(ids);

            String prefix = prefix(title);
            if (prefix.length() >= MIN_PREFIX && !lastName.isBlank()) {
                remaining = bounded - ids.size();
                add(ids, jdbc().queryForList("""
                        SELECT DISTINCT b.id
                        FROM books b
                        JOIN book_authors ba ON ba.book_id = b.id
                        JOIN authors a ON a.id = ba.author_id
                        WHERE b.id <> ? AND b.deleted = 0
                          AND lower(b.title) LIKE ? ESCAPE '\\'
                          AND lower(a.last_name) = lower(?)
                        ORDER BY b.id LIMIT ?
                        """, String.class, source.getId().asString(), escapeLike(prefix.toLowerCase(Locale.ROOT)) + "%", lastName, bounded), bounded);
            }
        }
        return List.copyOf(ids);
    }

    private static void add(Set<BookId> target, List<String> rawIds, int limit) {
        for (String raw : rawIds) {
            if (raw != null && !raw.isBlank()) target.add(BookId.fromString(raw));
            if (target.size() >= limit) break;
        }
    }

    private static String prefix(String value) {
        String normalized = safe(value).replaceAll("\\s+", " ");
        if (normalized.isBlank()) return "";
        int firstSpace = normalized.indexOf(' ');
        if (firstSpace >= MIN_PREFIX) return normalized.substring(0, Math.min(firstSpace, MAX_PREFIX));
        int length = Math.min(MAX_PREFIX, Math.min(normalized.length(), 8));
        return normalized.substring(0, length).strip();
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
