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
        List<Author> all = findAll();
        int updated = 0;
        for (Author author : all) {
            String searchName = buildSearchName(author);
            String sql = "UPDATE authors SET search_name = ? WHERE id = ?";
            int rows = getJdbcTemplate().update(sql, searchName, author.getId().asString());
            if (rows > 0) updated++;
        }
        log.info("Оновлено search_name для {} авторів", updated);
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
    @Transactional(readOnly = true)
    public List<Author> findAll() {
        String sql = "SELECT * FROM authors";
        return getJdbcTemplate().query(sql, authorRowMapper);
    }

    @Override
    @Transactional(readOnly = true)
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