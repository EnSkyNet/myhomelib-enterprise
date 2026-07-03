package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.cache.AuthorCache;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.infrastructure.persistence.mapper.AuthorRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.query.AuthorQueries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "app.database.type", havingValue = "sqlite", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SqliteAuthorRepository implements AuthorRepository {

    private final JdbcTemplate jdbcTemplate;
    private final AuthorRowMapper authorRowMapper;
    private final AuthorCache authorCache;

    @Override
    public List<Author> findAll() {
        // findAll не кешуємо — повертаємо всіх авторів
        return jdbcTemplate.query(AuthorQueries.FIND_ALL, authorRowMapper);
    }

    @Override
    public Optional<Author> findById(AuthorId id) {
        if (id == null) return Optional.empty();

        // 1. Перевіряємо кеш
        Optional<Author> cached = authorCache.get(id);
        if (cached.isPresent()) {
            log.debug("Автор знайдено в кеші: {}", id.asString());
            return cached;
        }

        // 2. Завантажуємо з БД
        try {
            Author author = jdbcTemplate.queryForObject(
                    AuthorQueries.FIND_BY_ID,
                    authorRowMapper,
                    id.asString()
            );
            // 3. Зберігаємо в кеш
            if (author != null) {
                authorCache.put(id, author);
            }
            return Optional.ofNullable(author);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Author save(Author author) {
        if (author.getId() == null) {
            author = new Author(
                    AuthorId.generate(),
                    author.getFirstName(),
                    author.getMiddleName(),
                    author.getLastName()
            );
        }

        String searchName = buildSearchName(author);
        jdbcTemplate.update(AuthorQueries.INSERT_OR_UPDATE_AUTHOR,
                author.getId().asString(),
                author.getFirstName(),
                author.getMiddleName(),
                author.getLastName(),
                searchName
        );

        // Оновлюємо кеш
        authorCache.put(author.getId(), author);

        log.debug("Автора збережено: id={}", author.getId().asString());
        return author;
    }

    @Override
    public void deleteById(AuthorId id) {
        jdbcTemplate.update(AuthorQueries.DELETE_BY_ID, id.asString());
        // Видаляємо з кешу
        authorCache.evict(id);
        log.debug("Автора видалено: id={}", id.asString());
    }

    @Override
    public Optional<Author> findByFullName(String firstName, String lastName) {
        // findByFullName не кешуємо — це пошук за ім'ям, зазвичай використовується рідко
        try {
            Author author = jdbcTemplate.queryForObject(
                    AuthorQueries.FIND_BY_FULL_NAME,
                    authorRowMapper,
                    firstName, lastName
            );
            return Optional.of(author);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private String buildSearchName(Author author) {
        return String.join(" ",
                author.getLastName() != null ? author.getLastName().toLowerCase() : "",
                author.getFirstName() != null ? author.getFirstName().toLowerCase() : "",
                author.getMiddleName() != null ? author.getMiddleName().toLowerCase() : ""
        ).trim().replaceAll("\\s+", " ");
    }
}