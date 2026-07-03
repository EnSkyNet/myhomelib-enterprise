package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
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

    private final JdbcTemplate jdbcTemplate;
    private final SeriesRowMapper seriesRowMapper;

    @Override
    public List<Series> findAll() {
        String sql = "SELECT id, name FROM series ORDER BY name";
        return jdbcTemplate.query(sql, seriesRowMapper);
    }

    @Override
    public Optional<Series> findById(SeriesId id) {
        String sql = "SELECT id, name FROM series WHERE id = ?";
        try {
            Series series = jdbcTemplate.queryForObject(sql, seriesRowMapper, id.asString());
            return Optional.of(series);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Series save(Series series) {
        if (series.getId() == null) {
            SeriesId newId = SeriesId.generate();
            String sql = "INSERT INTO series (id, name) VALUES (?, ?)";
            jdbcTemplate.update(sql, newId.asString(), series.getName());
            return new Series(newId, series.getName(), series.getDescription());
        } else {
            String sql = "UPDATE series SET name = ? WHERE id = ?";
            jdbcTemplate.update(sql, series.getName(), series.getId().asString());
            return series;
        }
    }

    @Override
    public void deleteById(SeriesId id) {
        jdbcTemplate.update("DELETE FROM series WHERE id = ?", id.asString());
    }

    @Override
    public List<String> getAllSeriesNames() {
        String sql = "SELECT DISTINCT TRIM(series) FROM books WHERE series IS NOT NULL AND TRIM(series) != '' ORDER BY TRIM(series)";
        return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getString(1));
    }
}