package com.myhomelibcorp.infrastructure.persistence.postgres;

import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

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
        log.warn("PostgresAuthorRepository.findAll() not implemented yet");
        return List.of();
    }

    @Override
    public Optional<Author> findById(AuthorId id) {
        log.warn("PostgresAuthorRepository.findById() not implemented yet");
        return Optional.empty();
    }

    @Override
    public Author save(Author author) {
        log.warn("PostgresAuthorRepository.save() not implemented yet");
        return author;
    }

    @Override
    public void deleteById(AuthorId id) {
        log.warn("PostgresAuthorRepository.deleteById() not implemented yet");
    }

    @Override
    public Optional<Author> findByFullName(String firstName, String lastName) {
        log.warn("PostgresAuthorRepository.findByFullName() not implemented yet");
        return Optional.empty();
    }

    @Override
    public List<Author> findFavorites(int limit) {
        log.warn("PostgresAuthorRepository.findFavorites() not implemented yet");
        return List.of();
    }

    @Override
    public long countOrphanedAuthors() {
        log.warn("PostgresAuthorRepository.countOrphanedAuthors() not implemented yet");
        return 0;
    }
}