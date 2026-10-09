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
        return CacheAsideRepositorySupport.findById(id, seriesCache, delegate::findById);
    }

    @Override
    public Optional<Series> findByName(String name) {
        return delegate.findByName(name);
    }

    @Override
    public Series save(Series series) {
        return CacheAsideRepositorySupport.saveRequired(
                series, seriesCache, delegate::save, Series::getId);
    }

    @Override
    public void deleteById(SeriesId id) {
        CacheAsideRepositorySupport.deleteById(id, seriesCache, delegate::deleteById);
    }

    @Override
    public List<String> getAllSeriesNames() {
        return delegate.getAllSeriesNames();
    }

    @Override
    public List<String> searchNames(String query, int limit) {
        return delegate.searchNames(query, limit);
    }

    @Override
    public void syncSeriesFromBooks() {
        log.debug("CachedSeriesRepository: делегування syncSeriesFromBooks до delegate");
        delegate.syncSeriesFromBooks();
    }
}