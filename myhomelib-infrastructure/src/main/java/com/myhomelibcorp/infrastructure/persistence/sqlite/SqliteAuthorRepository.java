package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
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

    private final JdbcTemplate jdbcTemplate;
    private final AuthorRowMapper authorRowMapper;

    @PostConstruct
    public void init() {
        try {
            jdbcTemplate.execute("ALTER TABLE authors ADD COLUMN search_name TEXT");
            log.info("Колонку search_name додано (якщо не існувала)");
        } catch (Exception e) {
            // Колонка вже існує
        }
        updateSearchNamesForAllAuthors();
    }

    private void updateSearchNamesForAllAuthors() {
        List<Author> all = findAll();
        int updated = 0;
        for (Author author : all) {
            String searchName = buildSearchName(author);
            String sql = "UPDATE authors SET search_name = ? WHERE id = ?";
            int rows = jdbcTemplate.update(sql, searchName, author.getId().asString());
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

    @Override
    public List<Author> findAll() {
        String sql = "SELECT * FROM authors";
        return jdbcTemplate.query(sql, authorRowMapper);
    }

    @Override
    public Optional<Author> findById(AuthorId id) {
        String sql = "SELECT * FROM authors WHERE id = ?";
        try {
            Author author = jdbcTemplate.queryForObject(sql, authorRowMapper, id.asString());
            return Optional.of(author);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Author save(Author author) {
        if (author.getId() == null) {
            author = new Author(AuthorId.generate(),
                    author.getFirstName(),
                    author.getMiddleName(),
                    author.getLastName());
        }
        String searchName = buildSearchName(author);
        String sql = """
            INSERT INTO authors (id, first_name, middle_name, last_name, search_name)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                first_name = excluded.first_name,
                middle_name = excluded.middle_name,
                last_name = excluded.last_name,
                search_name = excluded.search_name
            """;
        jdbcTemplate.update(sql,
                author.getId().asString(),
                author.getFirstName(),
                author.getMiddleName(),
                author.getLastName(),
                searchName);
        log.debug("Автора збережено: id={}, name={}", author.getId().asString(), author.getFullName());
        return author;
    }

    @Override
    public void deleteById(AuthorId id) {
        jdbcTemplate.update("DELETE FROM authors WHERE id = ?", id.asString());
        log.debug("Автора видалено: id={}", id.asString());
    }

    @Override
    public Optional<Author> findByFullName(String firstName, String lastName) {
        String sql = "SELECT * FROM authors WHERE first_name = ? AND last_name = ?";
        try {
            Author author = jdbcTemplate.queryForObject(sql, authorRowMapper, firstName, lastName);
            return Optional.of(author);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}