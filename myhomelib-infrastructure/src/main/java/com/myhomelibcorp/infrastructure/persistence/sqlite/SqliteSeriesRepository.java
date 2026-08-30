package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.SeriesRowMapper;
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
public class SqliteSeriesRepository implements SeriesRepository {

    private final CollectionManager collectionManager;
    private final SeriesRowMapper seriesRowMapper;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public List<Series> findAll() {
        String sql = "SELECT id, name FROM series ORDER BY name";
        return getJdbcTemplate().query(sql, seriesRowMapper);
    }

    @Override
    public Optional<Series> findById(SeriesId id) {
        String sql = "SELECT id, name FROM series WHERE id = ?";
        try {
            Series series = getJdbcTemplate().queryForObject(sql, seriesRowMapper, id.asString());
            return Optional.of(series);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<Series> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        try {
            Series series = getJdbcTemplate().queryForObject(
                    "SELECT id, name FROM series WHERE LOWER(TRIM(name)) = LOWER(TRIM(?)) ORDER BY id LIMIT 1",
                    seriesRowMapper, name);
            return Optional.ofNullable(series);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Series save(Series series) {
        if (series.getId() == null) {
            SeriesId newId = SeriesId.generate();
            String sql = "INSERT INTO series (id, name) VALUES (?, ?)";
            getJdbcTemplate().update(sql, newId.asString(), series.getName());
            return new Series(newId, series.getName(), series.getDescription());
        } else {
            String sql = "UPDATE series SET name = ? WHERE id = ?";
            getJdbcTemplate().update(sql, series.getName(), series.getId().asString());
            return series;
        }
    }

    @Override
    public void deleteById(SeriesId id) {
        getJdbcTemplate().update("DELETE FROM series WHERE id = ?", id.asString());
    }

    @Override
    public List<String> getAllSeriesNames() {
        String sql = """
                SELECT MIN(TRIM(series))
                FROM books
                WHERE series IS NOT NULL AND TRIM(series) <> ''
                GROUP BY LOWER(TRIM(series))
                ORDER BY LOWER(TRIM(series))
                """;
        return getJdbcTemplate().query(sql, (rs, rowNum) -> rs.getString(1));
    }

    @Override
    public List<String> searchNames(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        String pattern = "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
        return getJdbcTemplate().query("""
                SELECT name FROM series
                 WHERE LOWER(name) LIKE ?
                 ORDER BY LOWER(name), id
                 LIMIT ?
                """, (rs, rowNum) -> rs.getString(1), pattern, Math.min(limit, 200));
    }

    /**
     * Синхронізує таблицю series з даними з books.
     * Виправлено генерацію ID у форматі UUID з дефісами.
     */
    @Override
    public void syncSeriesFromBooks() {
        JdbcTemplate jt = getJdbcTemplate();
        try {
            // Генеруємо UUID у форматі xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
            String insertSql = """
                INSERT OR IGNORE INTO series (id, name)
                SELECT 
                    lower(hex(randomblob(4))) || '-' || 
                    lower(hex(randomblob(2))) || '-' || 
                    lower(hex(randomblob(2))) || '-' || 
                    lower(hex(randomblob(2))) || '-' || 
                    lower(hex(randomblob(6))),
                    MIN(TRIM(series))
                FROM books
                WHERE series IS NOT NULL 
                  AND TRIM(series) != ''
                  AND NOT EXISTS (
                      SELECT 1 FROM series s
                      WHERE LOWER(TRIM(s.name)) = LOWER(TRIM(books.series))
                  )
                GROUP BY LOWER(TRIM(series))
                """;
            int inserted = jt.update(insertSql);
            if (inserted > 0) {
                log.info("Синхронізовано {} нових серій з книг", inserted);
            } else {
                log.debug("Нових серій для синхронізації не знайдено");
            }
        } catch (Exception e) {
            log.error("Помилка синхронізації серій", e);
        }
    }
}