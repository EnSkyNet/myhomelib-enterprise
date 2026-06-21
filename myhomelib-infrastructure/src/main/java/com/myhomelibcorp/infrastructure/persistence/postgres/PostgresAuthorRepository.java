package com.myhomelibcorp.infrastructure.persistence.postgres;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
@Profile("postgres")
@RequiredArgsConstructor
@Slf4j
public class PostgresAuthorRepository implements AuthorRepository {

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
        log.warn("PostgresAuthorRepository.findAll() ще не реалізовано");
        return List.of();
    }

    @Override
    public Optional<Author> findById(AuthorId id) {
        log.warn("PostgresAuthorRepository.findById() ще не реалізовано");
        return Optional.empty();
    }

    @Override
    public Author save(Author author) {
        log.warn("PostgresAuthorRepository.save() ще не реалізовано");
        return author;
    }

    @Override
    public void deleteById(AuthorId id) {
        log.warn("PostgresAuthorRepository.deleteById() ще не реалізовано");
    }

    @Override
    public Optional<Author> findByFullName(String firstName, String lastName) {
        log.warn("PostgresAuthorRepository.findByFullName() ще не реалізовано");
        return Optional.empty();
    }
}