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
        String sql = "SELECT DISTINCT TRIM(series) FROM books WHERE series IS NOT NULL AND TRIM(series) != '' ORDER BY TRIM(series)";
        return getJdbcTemplate().query(sql, (rs, rowNum) -> rs.getString(1));
    }
}