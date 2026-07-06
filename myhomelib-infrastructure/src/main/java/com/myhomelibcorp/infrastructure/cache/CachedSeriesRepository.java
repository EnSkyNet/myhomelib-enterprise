package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.SeriesCache;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Primary
@RequiredArgsConstructor
@Slf4j
public class CachedSeriesRepository implements SeriesRepository {

    private final SeriesRepository delegate;
    private final SeriesCache seriesCache;

    @Override
    public List<Series> findAll() {
        return delegate.findAll();
    }

    @Override
    public Optional<Series> findById(SeriesId id) {
        Optional<Series> cached = seriesCache.get(id);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<Series> series = delegate.findById(id);
        series.ifPresent(s -> seriesCache.put(id, s));
        return series;
    }

    @Override
    public Series save(Series series) {
        Series saved = delegate.save(series);
        seriesCache.put(saved.getId(), saved);
        return saved;
    }

    @Override
    public void deleteById(SeriesId id) {
        delegate.deleteById(id);
        seriesCache.evict(id);
    }

    @Override
    public List<String> getAllSeriesNames() {
        return delegate.getAllSeriesNames();
    }
}