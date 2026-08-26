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
    public List<Author> findByInitial(char initial) {
        String expr = "COALESCE(NULLIF(BTRIM(last_name), ''), NULLIF(BTRIM(first_name), ''), BTRIM(COALESCE(middle_name, '')))";
        if (initial == '*') {
            return findFirstInitial().map(this::findByInitial).orElseGet(List::of);
        }
        if (initial == '#') {
            String sql = "SELECT * FROM authors WHERE " + expr + " <> '' AND SUBSTRING(" + expr + ", 1, 1) !~ '[[:alpha:]]' "
                    + "ORDER BY LOWER(COALESCE(last_name,'')), LOWER(COALESCE(first_name,'')), LOWER(COALESCE(middle_name,'')), id";
            return jdbcTemplate.query(sql, authorRowMapper);
        }
        String sql = "SELECT * FROM authors WHERE LOWER(SUBSTRING(" + expr + ", 1, 1)) = ? "
                + "ORDER BY LOWER(COALESCE(last_name,'')), LOWER(COALESCE(first_name,'')), LOWER(COALESCE(middle_name,'')), id";
        return jdbcTemplate.query(sql, authorRowMapper, String.valueOf(initial).toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public Optional<Character> findFirstInitial() {
        String expr = "COALESCE(NULLIF(BTRIM(last_name), ''), NULLIF(BTRIM(first_name), ''), BTRIM(COALESCE(middle_name, '')))";
        List<String> values = jdbcTemplate.query(
                "SELECT SUBSTRING(" + expr + ", 1, 1) AS initial FROM authors WHERE " + expr
                        + " <> '' ORDER BY LOWER(" + expr + ") LIMIT 1",
                (rs, rowNum) -> rs.getString("initial"));
        if (values.isEmpty() || values.getFirst() == null || values.getFirst().isBlank()) return Optional.empty();
        return Optional.of(values.getFirst().charAt(0));
    }

    @Override
    public long countByInitial(char initial) {
        if (initial == '*') {
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM authors", Long.class);
            return count == null ? 0L : count;
        }
        String expr = "COALESCE(NULLIF(BTRIM(last_name), ''), NULLIF(BTRIM(first_name), ''), BTRIM(COALESCE(middle_name, '')))";
        Long count;
        if (initial == '#') {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM authors WHERE " + expr + " <> '' AND SUBSTRING(" + expr + ", 1, 1) !~ '[[:alpha:]]'",
                    Long.class);
        } else {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM authors WHERE LOWER(SUBSTRING(" + expr + ", 1, 1)) = ?",
                    Long.class, String.valueOf(initial).toLowerCase(java.util.Locale.ROOT));
        }
        return count == null ? 0L : count;
    }

    @Override
    public List<Author> searchByName(String query, int limit) {
        if (query == null || query.isBlank()) return List.of();
        return jdbcTemplate.query(
                "SELECT * FROM authors WHERE LOWER(COALESCE(last_name,'') || ' ' || COALESCE(first_name,'') || ' ' || COALESCE(middle_name,'')) LIKE ? "
                        + "ORDER BY LOWER(COALESCE(last_name,'')), LOWER(COALESCE(first_name,'')), id LIMIT ?",
                authorRowMapper, "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%", Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public long countOrphanedAuthors() {
        log.warn("PostgresAuthorRepository.countOrphanedAuthors() not implemented yet");
        return 0;
    }
}