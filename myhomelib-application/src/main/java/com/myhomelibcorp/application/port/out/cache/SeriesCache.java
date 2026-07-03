package com.myhomelibcorp.application.port.out.cache;

import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;

import java.util.Optional;

public interface SeriesCache {
    Optional<Series> get(SeriesId id);
    void put(SeriesId id, Series series);
    void evict(SeriesId id);
    void clear();
}