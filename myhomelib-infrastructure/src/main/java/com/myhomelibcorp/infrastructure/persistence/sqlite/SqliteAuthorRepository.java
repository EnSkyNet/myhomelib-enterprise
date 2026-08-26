package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    // ---- МЕТОДИ ДЛЯ ІНІЦІАЛІЗАЦІЇ (викликаються після вибору колекції) ----
    public void addSearchNameColumnIfNotExists() {
        try {
            getJdbcTemplate().execute("ALTER TABLE authors ADD COLUMN search_name TEXT");
            log.info("Колонку search_name додано (якщо не існувала)");
        } catch (Exception e) {
            // Колонка вже існує
        }
    }

    public void updateSearchNamesForAllAuthors() {
        // Do this set-wise in SQLite. The previous implementation called findAll(),
        // materializing the complete author table on every collection initialization.
        String sql = """
                UPDATE authors
                SET search_name = TRIM(
                    COALESCE(last_name, '') || ' ' ||
                    COALESCE(first_name, '') || ' ' ||
                    COALESCE(middle_name, '')
                )
                WHERE search_name IS NULL
                   OR search_name <> TRIM(
                        COALESCE(last_name, '') || ' ' ||
                        COALESCE(first_name, '') || ' ' ||
                        COALESCE(middle_name, '')
                   )
                """;
        int updated = getJdbcTemplate().update(sql);
        log.info("Оновлено search_name для {} авторів без повного завантаження таблиці", updated);
    }

    private String buildSearchName(Author author) {
        return Stream.of(
                        author.getLastName(),
                        author.getFirstName(),
                        author.getMiddleName())
                .filter(Objects::nonNull)
                .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.joining(" "));
    }

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
        String searchName = buildSearchName(author);
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
        if (query == null || query.isBlank()) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 200));
        String needle = "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        String sql = """
                SELECT *
                FROM authors
                WHERE LOWER(COALESCE(search_name, '')) LIKE ?
                   OR LOWER(COALESCE(last_name, '') || ' ' || COALESCE(first_name, '') || ' ' || COALESCE(middle_name, '')) LIKE ?
                ORDER BY
                    COALESCE(last_name, '') COLLATE NOCASE,
                    COALESCE(first_name, '') COLLATE NOCASE,
                    COALESCE(middle_name, '') COLLATE NOCASE,
                    id
                LIMIT ?
                """;
        return getJdbcTemplate().query(sql, authorRowMapper, needle, needle, safeLimit);
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