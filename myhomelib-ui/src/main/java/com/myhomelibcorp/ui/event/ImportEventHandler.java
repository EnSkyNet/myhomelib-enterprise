package com.myhomelibcorp.ui.event;

import com.myhomelibcorp.application.event.ImportFinishedEvent;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteSeriesRepository;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportEventHandler {

    private final ApplicationState appState;
    private final StatisticsService statisticsService;
    private final SqliteSeriesRepository seriesRepository;
    private final BookLoaderService bookLoaderService;

    @PostConstruct
    public void init() {
        // Якщо використовуєте Spring Events, розкоментуйте @EventListener
        log.info("ImportEventHandler ініціалізовано");
    }

    // @EventListener – розкоментуйте, якщо використовуєте Spring Events
    public void onImportFinished(ImportFinishedEvent event) {
        log.info("Отримано подію ImportFinishedEvent: +{} книг", event.getImported());

        UiExecutor.runOnUiThread(() -> {
            // Оновлюємо статус
            statisticsService.refreshStatistics();
            String status;
            if (event.isSuccess()) {
                status = String.format("Імпорт завершено: +%d книг, помилок: %d",
                        event.getImported(), event.getErrors());
            } else {
                status = String.format("Імпорт завершено з помилками: +%d книг, помилок: %d",
                        event.getImported(), event.getErrors());
            }
            appState.getStatusBar().setStatusText(status);

            // Оновлюємо статистику
            var stats = statisticsService.getStatistics();
            appState.getStatusBar().setStatistics(stats);
            appState.getDashboard().setStatistics(stats);
            appState.getStatusBar().setStatusText("Імпорт завершено: +" + event.getImported() + " книг");

            // Приховуємо прогрес-бар
            appState.getStatusBar().setProgressVisible(false);

            // Синхронізуємо серії після імпорту
            try {
                seriesRepository.syncSeriesFromBooks();
                log.info("Серії синхронізовано після імпорту");
            } catch (Exception e) {
                log.error("Помилка синхронізації серій після імпорту", e);
            }

            // Оновлюємо список книг
            bookLoaderService.loadAllBooks();

        });
    }

    public void handleImportFinished(ImportFinishedEvent event) {
        onImportFinished(event);
    }
}