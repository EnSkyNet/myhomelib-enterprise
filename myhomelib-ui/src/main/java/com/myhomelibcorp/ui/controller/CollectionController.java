package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.service.CollectionManagementService;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.event.CollectionChangedEvent;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.presenter.CollectionPresenter;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionController {

    private final ApplicationState appState;
    private final CollectionPresenter collectionPresenter;
    private final SwitchCollectionUseCase switchCollectionUseCase;
    private final StatisticsService statisticsService;
    private final ApplicationEventPublisher eventPublisher;
    private final CollectionManagementService collectionManagementService;

    /**
     * Переключення на колекцію з асинхронною перебудовою індексу.
     * Індекс будується у фоновому потоці, UI не блокується.
     */
    public void switchToCollection(Collection collection, Runnable onComplete) {
        if (collection == null) {
            log.warn("Спроба переключитися на null колекцію");
            return;
        }
        log.info("Переключення на колекцію: {}", collection.getName());

        // Lifecycle owns the reuse-vs-rebuild policy for the collection-specific index.
        Collection activated = switchCollectionUseCase.execute(collection, true);
        appState.setCurrentLibraryCollection(activated);
        statisticsService.refreshStatistics();
        appState.getStatusBar().setStatistics(statisticsService.getStatistics());
        appState.getStatusBar().setStatusText("Переключено на колекцію: " + activated.getName()
                + ". Пошуковий індекс перевірено; оновлення виконується у фоні лише за потреби.");
        appState.getStatusBar().setProgressVisible(false);

        eventPublisher.publishEvent(new CollectionChangedEvent(activated));
        if (onComplete != null) {
            onComplete.run();
        }
    }

    public void handleNewCollection(Stage owner, Runnable onComplete) {
        collectionPresenter.showCreateCollectionDialog(
                javafx.collections.FXCollections.observableArrayList(), owner);
        eventPublisher.publishEvent(new NavigationRefreshEvent());
        if (onComplete != null) {
            onComplete.run();
        }
    }



    public void handleSelectCollection(Runnable onComplete) {
        Collection current = appState.getCurrentLibraryCollection();
        if (current != null) {
            switchToCollection(current, onComplete);
        }
    }

    // ===== Delegated methods to CollectionManagementService =====

    public Collection getCurrentCollection() {
        return collectionManagementService.getCurrentCollection();
    }

    public boolean hasActiveCollection() {
        return collectionManagementService.hasActiveCollection();
    }

    public boolean isCollectionReady() {
        return collectionManagementService.isCollectionReady();
    }

    public long getDatabaseSize() {
        return collectionManagementService.getDatabaseSize();
    }
}