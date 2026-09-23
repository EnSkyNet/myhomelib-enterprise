package com.myhomelibcorp.ui.event;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.event.ImportFinishedEvent;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.service.PostImportMaintenanceService;
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
    private final PostImportMaintenanceService postImportMaintenanceService;
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
        executor.submit(() -> postImportMaintenanceService.refreshAfterImport(event.collectionId()))
                .thenAccept(stats -> UiExecutor.runOnUiThread(() -> {
            String eventCollectionId = event.collectionId() == null ? "" : event.collectionId().trim();
            var currentCollection = appState.getCurrentLibraryCollection();
            if (!eventCollectionId.isBlank()
                    && (currentCollection == null || !eventCollectionId.equals(currentCollection.getId()))) {
                log.debug("Пропущено UI refresh завершеного імпорту для вже неактивної колекції {}", eventCollectionId);
                return;
            }
            appState.getStatusBar().setStatistics(stats);
            appState.getDashboard().setStatistics(stats);

            String status = event.isSuccess()
                    ? String.format(UiText.text("ui.java.import_event_handler.035b739d32"), event.getImported(), event.getErrors())
                    : String.format(UiText.text("ui.java.import_event_handler.8dc48433e3"), event.getImported(), event.getErrors());
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
            String eventCollectionId = event.collectionId() == null ? "" : event.collectionId().trim();
            UiExecutor.runOnUiThread(() -> {
                var currentCollection = appState.getCurrentLibraryCollection();
                if (!eventCollectionId.isBlank()
                        && (currentCollection == null || !eventCollectionId.equals(currentCollection.getId()))) {
                    log.debug("Пропущено stale post-import error для вже неактивної колекції {}", eventCollectionId);
                    return;
                }
                appState.getStatusBar().setProgressVisible(false);
                appState.getStatusBar().setStatusText(UiText.text("ui.java.import_event_handler.cf10b5740a"));
            });
            return null;
        });

        String importedCollectionId = event.collectionId() == null ? "" : event.collectionId().trim();
        if (importedCollectionId.isBlank() && appState.getCurrentLibraryCollection() != null) {
            importedCollectionId = appState.getCurrentLibraryCollection().getId();
        }
        if (event.isSuccess() && event.source() != null && !importedCollectionId.isBlank()) {
            collectionAutoUpdateUseCase.markApplied(importedCollectionId, event.source())
                    .exceptionally(error -> {
                        log.debug("Imported file is not the configured collection source: {}", error.getMessage());
                        return null;
                    });
        }
    }

}