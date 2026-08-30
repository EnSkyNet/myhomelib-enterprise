package com.myhomelibcorp.application.port.out.statistics;

import com.myhomelibcorp.application.dto.ReadingSessionRecord;
import com.myhomelibcorp.application.dto.ReadingStatisticsDto;

import java.util.Optional;

/** Persistence boundary for aggregate Reader statistics. */
public interface ReadingStatisticsPort {
    /** Atomically merges one completed session into the book aggregate. */
    void recordSession(ReadingSessionRecord session);

    Optional<ReadingStatisticsDto> findByBookId(String bookId);

    void deleteByBookId(String bookId);
}
