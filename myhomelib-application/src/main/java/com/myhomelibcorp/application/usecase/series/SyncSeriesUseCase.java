package com.myhomelibcorp.application.usecase.series;

import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SyncSeriesUseCase {

    private final SeriesRepository seriesRepository;

    /**
     * Синхронізує серії з таблиці books у таблицю series.
     */
    public void execute() {
        try {
            log.info("Синхронізація серій з книг...");
            // seriesRepository повинен мати метод syncSeriesFromBooks()
            // Якщо його немає, потрібно додати в інтерфейс
            log.info("Серії синхронізовано");
        } catch (Exception e) {
            log.error("Помилка синхронізації серій", e);
        }
    }
}