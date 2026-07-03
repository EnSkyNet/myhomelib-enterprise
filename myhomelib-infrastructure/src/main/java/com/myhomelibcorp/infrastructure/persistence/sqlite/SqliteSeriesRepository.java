package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.cache.SeriesCache;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import com.myhomelibcorp.infrastructure.persistence.mapper.SeriesRowMapper;
import com.myhomelibcorp.infrastructure.persistence.sqlite.query.SeriesQueries;
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
    private final SeriesCache seriesCache;

    @Override
    public List<Series> findAll() {
        return jdbcTemplate.query(SeriesQueries.FIND_ALL, seriesRowMapper);
    }

    @Override
    public Optional<Series> findById(SeriesId id) {
        if (id == null) return Optional.empty();

        Optional<Series> cached = seriesCache.get(id);
        if (cached.isPresent()) {
            log.debug("Серію знайдено в кеші: {}", id.asString());
            return cached;
        }

        try {
            Series series = jdbcTemplate.queryForObject(
                    SeriesQueries.FIND_BY_ID,
                    seriesRowMapper,
                    id.asString()
            );
            if (series != null) {
                seriesCache.put(id, series);
            }
            return Optional.ofNullable(series);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Series save(Series series) {
        if (series.getId() == null) {
            SeriesId newId = SeriesId.generate();
            jdbcTemplate.update(SeriesQueries.INSERT_SERIES, newId.asString(), series.getName());
            series = new Series(newId, series.getName(), series.getDescription());
        } else {
            jdbcTemplate.update(SeriesQueries.UPDATE_SERIES, series.getName(), series.getId().asString());
        }

        seriesCache.put(series.getId(), series);
        log.debug("Серію збережено: id={}", series.getId().asString());
        return series;
    }

    @Override
    public void deleteById(SeriesId id) {
        jdbcTemplate.update(SeriesQueries.DELETE_BY_ID, id.asString());
        seriesCache.evict(id);
        log.debug("Серію видалено: id={}", id.asString());
    }

    @Override
    public List<String> getAllSeriesNames() {
        return jdbcTemplate.query(SeriesQueries.FIND_DISTINCT_NAMES, (rs, rowNum) -> rs.getString(1));
    }
}