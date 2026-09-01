package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.AuthorSearchNameNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@ConditionalOnProperty(name = "app.database.type", havingValue = "sqlite", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SqliteAuthorRepository implements AuthorRepository {

    private final CollectionManager collectionManager;
    private final AuthorRowMapper authorRowMapper;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    private static final String SEARCH_NAME_NORMALIZATION_MARKER = "v71_author_search_name_unicode_normalized";
    private static final int SEARCH_NAME_BACKFILL_BATCH = 1000;

    /**
     * One-time, memory-bounded Unicode normalization for legacy author search keys.
     * The marker is written only after the full keyset pass completes, so an interrupted
     * run is safely repeatable.
     */
    public long normalizeSearchNamesIfNeeded() {
        JdbcTemplate jdbc = getJdbcTemplate();
        String marker = jdbc.query(
                "SELECT value FROM settings WHERE key=?",
                rs -> rs.next() ? rs.getString(1) : null,
                SEARCH_NAME_NORMALIZATION_MARKER);
        if ("1".equals(marker)) return 0L;

        long updated = 0L;
        String afterId = "";
        while (true) {
            List<AuthorSearchRow> rows = jdbc.query(
                    "SELECT id, first_name, middle_name, last_name FROM authors WHERE id>? ORDER BY id LIMIT ?",
                    (rs, rowNum) -> new AuthorSearchRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                    afterId, SEARCH_NAME_BACKFILL_BATCH);
            if (rows.isEmpty()) break;

            List<Object[]> batch = rows.stream()
                    .map(row -> new Object[]{AuthorSearchNameNormalizer.normalize(row.firstName(), row.middleName(), row.lastName()), row.id()})
                    .toList();
            jdbc.batchUpdate("UPDATE authors SET search_name=? WHERE id=?", batch);
            updated += rows.size();
            afterId = rows.get(rows.size() - 1).id();
        }

        jdbc.update("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                SEARCH_NAME_NORMALIZATION_MARKER, "1");
        log.info("Одноразово нормалізовано Unicode search_name для {} авторів", updated);
        return updated;
    }

    private record AuthorSearchRow(String id, String firstName, String middleName, String lastName) {}

    // ---- ОСНОВНІ МЕТОДИ РЕПОЗИТОРІЮ ----
    @Override
    @Transactional(transactionManager = "collectionTransactionManager", readOnly = true)
    public List<Author> findAll() {
        String sql = "SELECT * FROM authors";
        return getJdbcTemplate().query(sql, authorRowMapper);
    }

    @Override
    @Transactional(transactionManager = "collectionTransactionManager", readOnly = true)
    public Optional<Author> findById(AuthorId id) {
        String sql = "SELECT * FROM authors WHERE id = ?";
        try {
            Author author = getJdbcTemplate().queryForObject(sql, authorRowMapper, id.asString());
            return Optional.of(author);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Author save(Author author) {
        if (author.getId() == null) {
            author = new Author(AuthorId.generate(),
                    author.getFirstName(), author.getMiddleName(), author.getLastName(), author.getAnnotation());
        }
        String searchName = AuthorSearchNameNormalizer.normalize(
                author.getFirstName(), author.getMiddleName(), author.getLastName());
        String sql = """
            INSERT INTO authors (id, first_name, middle_name, last_name, search_name, annotation)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                first_name = excluded.first_name,
                middle_name = excluded.middle_name,
                last_name = excluded.last_name,
                search_name = excluded.search_name,
                annotation = excluded.annotation
            """;
        getJdbcTemplate().update(sql,
                author.getId().asString(),
                author.getFirstName(),
                author.getMiddleName(),
                author.getLastName(),
                searchName,
                author.getAnnotation());
        log.debug("Автора збережено: id={}, name={}", author.getId().asString(), author.getFullName());
        return author;
    }

    @Override
    public void deleteById(AuthorId id) {
        getJdbcTemplate().update("DELETE FROM authors WHERE id = ?", id.asString());
        log.debug("Автора видалено: id={}", id.asString());
    }

    /**
     * Пошук автора за ім'ям та прізвищем.
     * Якщо знайдено кілька записів з однаковими first_name та last_name,
     * повертає першого та логує попередження.
     */
    @Override
    public Optional<Author> findByFullName(String firstName, String lastName) {
        String sql = "SELECT * FROM authors WHERE first_name = ? AND last_name = ?";
        try {
            List<Author> authors = getJdbcTemplate().query(sql, authorRowMapper, firstName, lastName);
            if (authors.isEmpty()) {
                return Optional.empty();
            } else if (authors.size() == 1) {
                return Optional.of(authors.get(0));
            } else {
                log.warn("Знайдено {} авторів з ім'ям '{}' та прізвищем '{}', повертаємо першого",
                        authors.size(), firstName, lastName);
                return Optional.of(authors.get(0));
            }
        } catch (Exception e) {
            log.error("Помилка пошуку автора за іменем: {} {}", firstName, lastName, e);
            return Optional.empty();
        }
    }
    @Override
    public Optional<Author> findByName(String firstName, String middleName, String lastName) {
        String sql = """
                SELECT * FROM authors
                 WHERE first_name = ? AND middle_name = ? AND last_name = ?
                 ORDER BY id
                 LIMIT 1
                """;
        try {
            Author author = getJdbcTemplate().queryForObject(
                    sql, authorRowMapper, safe(firstName), safe(middleName), safe(lastName));
            return Optional.ofNullable(author);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    @Override
    public List<Author> findFavorites(int limit) {
        String sql = """
            SELECT a.* FROM authors a
            JOIN book_authors ba ON a.id = ba.author_id
            JOIN book_groups bg ON bg.book_id = ba.book_id
            JOIN groups g ON g.id = bg.group_id
            WHERE LOWER(TRIM(g.name)) IN ('favorites','обране','избрани','улюблене')
            GROUP BY a.id
            ORDER BY COUNT(DISTINCT ba.book_id) DESC, a.last_name COLLATE NOCASE, a.first_name COLLATE NOCASE
            LIMIT ?
            """;
        return getJdbcTemplate().query(sql, authorRowMapper, Math.max(1, limit));
    }

    private static final String AUTHOR_DISPLAY_EXPR = """
            CASE
                WHEN TRIM(COALESCE(last_name, '')) <> '' THEN TRIM(last_name)
                WHEN TRIM(COALESCE(first_name, '')) <> '' THEN TRIM(first_name)
                ELSE TRIM(COALESCE(middle_name, ''))
            END
            """;

    private static final String TOOLBAR_INITIALS =
            "АБВГҐДЕЁЄЖЗИІЇЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯABCDEFGHIJKLMNOPQRSTUVWXYZ";

    @Override
    @Transactional(transactionManager = "collectionTransactionManager", readOnly = true)
    public List<Author> findByInitial(char initial) {
        if (initial == '*') {
            return findFirstInitial().map(this::findByInitial).orElseGet(List::of);
        }

        String initialExpr = "SUBSTR((" + AUTHOR_DISPLAY_EXPR + "), 1, 1)";
        String where;
        Object[] args;
        if (initial == '#') {
            String supported = TOOLBAR_INITIALS + TOOLBAR_INITIALS.toLowerCase(java.util.Locale.ROOT);
            String placeholders = java.util.stream.IntStream.range(0, supported.length())
                    .mapToObj(i -> "?")
                    .collect(java.util.stream.Collectors.joining(","));
            where = initialExpr + " NOT IN (" + placeholders + ") AND " + initialExpr + " <> ''";
            args = supported.chars().mapToObj(c -> String.valueOf((char) c)).toArray();
        } else {
            String upper = String.valueOf(Character.toUpperCase(initial));
            String lower = String.valueOf(Character.toLowerCase(initial));
            where = initialExpr + " IN (?, ?)";
            args = new Object[]{upper, lower};
        }

        String sql = """
                SELECT *
                FROM authors
                WHERE %s
                ORDER BY
                    COALESCE(last_name, '') COLLATE NOCASE,
                    COALESCE(first_name, '') COLLATE NOCASE,
                    COALESCE(middle_name, '') COLLATE NOCASE,
                    id
                """.formatted(where);

        return getJdbcTemplate().query(sql, authorRowMapper, args);
    }

    @Override
    @Transactional(transactionManager = "collectionTransactionManager", readOnly = true)
    public Optional<Character> findFirstInitial() {
        for (char initial : TOOLBAR_INITIALS.toCharArray()) {
            if (countByInitial(initial) > 0) {
                return Optional.of(initial);
            }
        }
        if (countByInitial('#') > 0) {
            return Optional.of('#');
        }
        return Optional.empty();
    }

    @Override
    @Transactional(transactionManager = "collectionTransactionManager", readOnly = true)
    public long countByInitial(char initial) {
        if (initial == '*') {
            Long count = getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM authors", Long.class);
            return count == null ? 0L : count;
        }

        String initialExpr = "SUBSTR((" + AUTHOR_DISPLAY_EXPR + "), 1, 1)";
        Long count;
        if (initial == '#') {
            String supported = TOOLBAR_INITIALS + TOOLBAR_INITIALS.toLowerCase(java.util.Locale.ROOT);
            String placeholders = java.util.stream.IntStream.range(0, supported.length())
                    .mapToObj(i -> "?")
                    .collect(java.util.stream.Collectors.joining(","));
            String sql = "SELECT COUNT(*) FROM authors WHERE " + initialExpr
                    + " NOT IN (" + placeholders + ") AND " + initialExpr + " <> ''";
            Object[] args = supported.chars().mapToObj(c -> String.valueOf((char) c)).toArray();
            count = getJdbcTemplate().queryForObject(sql, Long.class, args);
        } else {
            String upper = String.valueOf(Character.toUpperCase(initial));
            String lower = String.valueOf(Character.toLowerCase(initial));
            String sql = "SELECT COUNT(*) FROM authors WHERE " + initialExpr + " IN (?, ?)";
            count = getJdbcTemplate().queryForObject(sql, Long.class, upper, lower);
        }
        return count == null ? 0L : count;
    }

    @Override
    @Transactional(transactionManager = "collectionTransactionManager", readOnly = true)
    public List<Author> searchByName(String query, int limit) {
        return searchByName(query, limit, 0);
    }

    @Override
    @Transactional(transactionManager = "collectionTransactionManager", readOnly = true)
    public List<Author> searchByName(String query, int limit, int offset) {
        if (query == null || query.isBlank()) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeOffset = Math.max(0, offset);
        String needle = "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        String sql = """
                SELECT *
                FROM authors
                WHERE COALESCE(search_name, '') LIKE ?
                   OR LOWER(COALESCE(last_name, '') || ' ' || COALESCE(first_name, '') || ' ' || COALESCE(middle_name, '')) LIKE ?
                ORDER BY
                    COALESCE(last_name, '') COLLATE NOCASE,
                    COALESCE(first_name, '') COLLATE NOCASE,
                    COALESCE(middle_name, '') COLLATE NOCASE,
                    id
                LIMIT ? OFFSET ?
                """;
        return getJdbcTemplate().query(sql, authorRowMapper, needle, needle, safeLimit, safeOffset);
    }

    @Override
    public long countOrphanedAuthors() {
        try {
            String sql = """
                SELECT COUNT(*) FROM authors a
                WHERE NOT EXISTS (
                    SELECT 1 FROM book_authors ba WHERE ba.author_id = a.id
                )
                """;
            return getJdbcTemplate().queryForObject(sql, Long.class);
        } catch (Exception e) {
            log.warn("Не вдалося підрахувати кількість авторів без книг", e);
            return 0;
        }
    }
}