package com.myhomelibcorp.ui.event;

import com.myhomelibcorp.application.event.ImportFinishedEvent;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteSeriesRepository;
import com.myhomelibcorp.ui.controller.MainController;
import com.myhomelibcorp.ui.navigation.NavigationController;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportEventHandler {

    private final ApplicationState appState;
    private final StatisticsService statisticsService;
    private final SqliteSeriesRepository seriesRepository;
    private final BookLoaderService bookLoaderService;
    private final NavigationController navigationController; // <-- додано
    private final MainController mainController; // <-- додано

    @PostConstruct
    public void init() {
        log.info("ImportEventHandler ініціалізовано");
    }

    @EventListener
    public void onImportFinished(ImportFinishedEvent event) {
        log.info("Отримано подію ImportFinishedEvent: +{} книг", event.getImported());

        UiExecutor.runOnUiThread(() -> {
            // 1. Оновлюємо статистику
            statisticsService.refreshStatistics();
            var stats = statisticsService.getStatistics();
            appState.getStatusBar().setStatistics(stats);
            appState.getDashboard().setStatistics(stats);

            // 2. Оновлюємо статус-бар
            String status;
            if (event.isSuccess()) {
                status = String.format("Імпорт завершено: +%d книг, помилок: %d",
                        event.getImported(), event.getErrors());
            } else {
                status = String.format("Імпорт завершено з помилками: +%d книг, помилок: %d",
                        event.getImported(), event.getErrors());
            }
            appState.getStatusBar().setStatusText(status);
            appState.getStatusBar().setProgressVisible(false);

            // 3. Синхронізуємо серії
            try {
                seriesRepository.syncSeriesFromBooks();
                log.info("Серії синхронізовано після імпорту");
            } catch (Exception e) {
                log.error("Помилка синхронізації серій після імпорту", e);
            }

            // 4. Оновлюємо список книг
            bookLoaderService.loadAllBooks();

            // 5. Оновлюємо навігаційне дерево
            navigationController.refreshNavigation();

            // 6. Показуємо дашборд, щоб оновити головну сторінку
            mainController.showDashboard();

            log.info("Оновлення UI після імпорту завершено");
        });
    }
}