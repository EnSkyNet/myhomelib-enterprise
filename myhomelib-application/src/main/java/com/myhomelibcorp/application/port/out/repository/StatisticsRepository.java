package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.application.dto.LibraryStatistics;

public interface StatisticsRepository {
    LibraryStatistics getStatistics();
    void refreshStatistics();

    /** Marks cached aggregate statistics stale after a cheap point mutation. */
    void invalidate();
}