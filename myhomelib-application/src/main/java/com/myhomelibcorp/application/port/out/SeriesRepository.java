package com.myhomelibcorp.application.port.out;

import com.myhomelibcorp.domain.model.series.Series;

import java.util.List;
import java.util.Optional;

public interface SeriesRepository {
    List<Series> findAll();
    Optional<Series> findById(String id);
    Series save(Series series);
    void deleteById(String id);
}