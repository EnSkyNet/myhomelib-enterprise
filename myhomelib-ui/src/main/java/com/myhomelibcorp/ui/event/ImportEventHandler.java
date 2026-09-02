package com.myhomelibcorp.ui.event;

import com.myhomelibcorp.application.event.ImportFinishedEvent;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.usecase.series.SyncSeriesUseCase;
import com.myhomelibcorp.application.usecase.collection.CollectionAutoUpdateUseCase;
import com.myhomelibcorp.ui.navigation.NavigationPanelController;
import com.myhomelibcorp.ui.service.BookLoaderService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
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
    private final SyncSeriesUseCase syncSeriesUseCase;
    private final BookLoaderService bookLoaderService;
    private final NavigationPanelController navigationPanelController;
    private final CollectionAutoUpdateUseCase collectionAutoUpdateUseCase;
    private final UiBackgroundExecutor executor;

    @EventListener
    public void onImportFinished(ImportFinishedEvent event) {
        log.info("Отримано подію ImportFinishedEvent: +{} книг", event.getImported());

        // Mark the aggregate cache stale immediately. The expensive refresh still runs in background.
        statisticsService.invalidate();

        // Large-library post-processing must not run on the JavaFX Application Thread.
        executor.submit(() -> {
            try {
                syncSeriesUseCase.execute();
                log.info("Серії синхронізовано після імпорту");
            } catch (Exception e) {
                log.error("Помилка синхронізації серій після імпорту", e);
            }
            statisticsService.refreshStatistics();
            return statisticsService.getStatistics();
        }).thenAccept(stats -> UiExecutor.runOnUiThread(() -> {
            appState.getStatusBar().setStatistics(stats);
            appState.getDashboard().setStatistics(stats);

            String status = event.isSuccess()
                    ? String.format("Імпорт завершено: +%d книг, помилок: %d", event.getImported(), event.getErrors())
                    : String.format("Імпорт завершено з помилками: +%d книг, помилок: %d", event.getImported(), event.getErrors());
            appState.getStatusBar().setStatusText(status);
            appState.getStatusBar().setProgressVisible(false);

            try {
                bookLoaderService.reloadLastQuery();
                log.info("Список книг оновлено після імпорту");
            } catch (Exception e) {
                log.error("Помилка оновлення списку книг", e);
            }
            try {
                navigationPanelController.refreshAll();
                log.info("Навігацію оновлено після імпорту");
            } catch (Exception e) {
                log.error("Помилка оновлення навігації", e);
            }
            log.info("Оновлення UI після імпорту завершено");
        })).exceptionally(error -> {
            log.error("Помилка post-import refresh", error);
            UiExecutor.runOnUiThread(() -> {
                appState.getStatusBar().setProgressVisible(false);
                appState.getStatusBar().setStatusText("Імпорт завершено, але не всі підсумкові дані вдалося оновити");
            });
            return null;
        });

        if (event.isSuccess() && event.source() != null && appState.getCurrentLibraryCollection() != null) {
            collectionAutoUpdateUseCase.markApplied(appState.getCurrentLibraryCollection().getId(), event.source())
                    .exceptionally(error -> {
                        log.debug("Imported file is not the configured collection source: {}", error.getMessage());
                        return null;
                    });
        }
    }

}