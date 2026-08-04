package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;

import java.util.List;
import java.util.Optional;

public interface SeriesRepository {
    List<Series> findAll();
    Optional<Series> findById(SeriesId id);
    Series save(Series series);
    void deleteById(SeriesId id);
    List<String> getAllSeriesNames();

    /**
     * Синхронізує серії з таблиці books у таблицю series.
     */
    void syncSeriesFromBooks();
}