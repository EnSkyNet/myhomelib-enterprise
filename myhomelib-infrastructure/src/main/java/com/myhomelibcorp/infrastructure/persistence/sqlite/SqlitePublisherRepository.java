package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.PublisherRepository;
import com.myhomelibcorp.domain.model.publisher.Publisher;
import com.myhomelibcorp.domain.model.valueobject.PublisherId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class SqlitePublisherRepository implements PublisherRepository {

    private final CollectionManager collectionManager;

    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final RowMapper<Publisher> publisherRowMapper = (rs, rowNum) -> {
        PublisherId id = PublisherId.fromString(rs.getString("id"));
        String name = rs.getString("name");
        String description = rs.getString("description");
        String website = rs.getString("website");
        LocalDateTime createdAt = parseDate(rs.getString("created_at"));
        return new Publisher(id, name, description, website, createdAt);
    };

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public List<Publisher> findAll() {
        String sql = "SELECT id, name, description, website, created_at FROM publishers ORDER BY name";
        return getJdbcTemplate().query(sql, publisherRowMapper);
    }

    @Override
    public Optional<Publisher> findById(PublisherId id) {
        String sql = "SELECT id, name, description, website, created_at FROM publishers WHERE id = ?";
        try {
            Publisher publisher = getJdbcTemplate().queryForObject(sql, publisherRowMapper, id.asString());
            return Optional.of(publisher);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Publisher> findByName(String name) {
        String sql = "SELECT id, name, description, website, created_at FROM publishers WHERE name = ?";
        try {
            Publisher publisher = getJdbcTemplate().queryForObject(sql, publisherRowMapper, name);
            return Optional.of(publisher);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Publisher save(Publisher publisher) {
        if (publisher.getId() == null) {
            // Новий видавець
            PublisherId newId = PublisherId.generate();
            String sql = """
                INSERT INTO publishers (id, name, description, website, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
            getJdbcTemplate().update(sql,
                    newId.asString(),
                    publisher.getName(),
                    publisher.getDescription(),
                    publisher.getWebsite(),
                    LocalDateTime.now().format(DATE_FORMATTER)
            );
            return new Publisher(newId, publisher.getName(), publisher.getDescription(),
                    publisher.getWebsite(), LocalDateTime.now());
        } else {
            // Оновлення існуючого
            String sql = """
                UPDATE publishers SET name = ?, description = ?, website = ?
                WHERE id = ?
                """;
            getJdbcTemplate().update(sql,
                    publisher.getName(),
                    publisher.getDescription(),
                    publisher.getWebsite(),
                    publisher.getId().asString()
            );
            return publisher;
        }
    }

    @Override
    public void deleteById(PublisherId id) {
        getJdbcTemplate().update("DELETE FROM publishers WHERE id = ?", id.asString());
    }

    @Override
    public long count() {
        return getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM publishers", Long.class);
    }

    @Override
    public List<Publisher> findTop(int limit) {
        String sql = """
            SELECT p.id, p.name, p.description, p.website, p.created_at
            FROM publishers p
            WHERE EXISTS (SELECT 1 FROM books b WHERE b.publisher = p.name)
            ORDER BY (SELECT COUNT(*) FROM books b WHERE b.publisher = p.name) DESC
            LIMIT ?
            """;
        return getJdbcTemplate().query(sql, publisherRowMapper, limit);
    }

    @Override
    public List<Publisher> findByNameContaining(String name) {
        String sql = "SELECT id, name, description, website, created_at FROM publishers WHERE name LIKE ? ORDER BY name";
        String pattern = "%" + name + "%";
        return getJdbcTemplate().query(sql, publisherRowMapper, pattern);
    }

    private LocalDateTime parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(dateStr, DATE_FORMATTER);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(dateStr);
            } catch (Exception ex) {
                return LocalDateTime.now();
            }
        }
    }
}