package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.SeriesRepository;
import com.myhomelibcorp.domain.model.series.Series;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(name = "app.database.type", havingValue = "sqlite", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SqliteSeriesRepository implements SeriesRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<Series> seriesRowMapper = (rs, rowNum) -> {
        String id = rs.getString("id");
        String name = rs.getString("name");
        // Якщо є поле description, додати
        return new Series(id, name, null);
    };

    @Override
    public List<Series> findAll() {
        String sql = "SELECT id, name FROM series ORDER BY name";
        return jdbcTemplate.query(sql, seriesRowMapper);
    }

    @Override
    public Optional<Series> findById(String id) {
        String sql = "SELECT id, name FROM series WHERE id = ?";
        try {
            Series series = jdbcTemplate.queryForObject(sql, seriesRowMapper, id);
            return Optional.of(series);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Series save(Series series) {
        if (series.getId() == null) {
            // Вставляємо нову серію з генерованим UUID або використовуємо автоінкремент?
            // У нас рядок id, тому згенеруємо
            String newId = java.util.UUID.randomUUID().toString();
            String sql = "INSERT INTO series (id, name) VALUES (?, ?)";
            jdbcTemplate.update(sql, newId, series.getName());
            return new Series(newId, series.getName(), series.getDescription());
        } else {
            String sql = "UPDATE series SET name = ? WHERE id = ?";
            jdbcTemplate.update(sql, series.getName(), series.getId());
            return series;
        }
    }

    @Override
    public void deleteById(String id) {
        jdbcTemplate.update("DELETE FROM series WHERE id = ?", id);
    }
}