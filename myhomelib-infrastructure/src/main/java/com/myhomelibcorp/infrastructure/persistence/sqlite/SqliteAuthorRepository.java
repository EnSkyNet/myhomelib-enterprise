package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqliteAuthorRepository implements AuthorRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<Author> authorRowMapper = (rs, rowNum) -> {
        AuthorId id = AuthorId.fromString(rs.getString("id"));
        return new Author(id,
                rs.getString("first_name"),
                rs.getString("middle_name"),
                rs.getString("last_name"));
    };

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
        String sql = """
            INSERT OR REPLACE INTO authors (id, first_name, middle_name, last_name)
            VALUES (?, ?, ?, ?)
            """;
        jdbcTemplate.update(sql,
                author.getId().asString(),
                author.getFirstName(),
                author.getMiddleName(),
                author.getLastName());
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