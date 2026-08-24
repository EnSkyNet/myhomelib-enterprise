package com.myhomelibcorp.application.usecase.series;

import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SyncSeriesUseCase {

    private final SeriesRepository seriesRepository;

    public SyncSeriesUseCase(SeriesRepository seriesRepository) {
        this.seriesRepository = seriesRepository;
    }

    /**
     * Synchronizes persisted series identities from the canonical books table.
     * This is required after imports so navigation can use stable SeriesId values.
     */
    public void execute() {
        log.info("Синхронізація серій з книг...");
        seriesRepository.syncSeriesFromBooks();
        log.info("Серії синхронізовано");
    }
}
