package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.service.CollectionManagementService;
import com.myhomelibcorp.application.service.DatabaseToolsService;
import com.myhomelibcorp.application.statistics.StatisticsService;
import com.myhomelibcorp.application.usecase.collection.DeleteCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.RenameCollectionUseCase;
import com.myhomelibcorp.application.usecase.collection.SwitchCollectionUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.event.CollectionChangedEvent;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.presenter.CollectionPresenter;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CollectionController {

    private final ApplicationState appState;
    private final CollectionPresenter collectionPresenter;
    private final DialogService dialogService;
    private final SwitchCollectionUseCase switchCollectionUseCase;
    private final RenameCollectionUseCase renameCollectionUseCase;
    private final DeleteCollectionUseCase deleteCollectionUseCase;
    private final StatisticsService statisticsService;
    private final ApplicationEventPublisher eventPublisher;
    private final CollectionManagementService collectionManagementService;

    public void switchToCollection(Collection collection, Runnable onComplete) {
        if (collection == null) {
            log.warn("Спроба переключитися на null колекцію");
            return;
        }
        log.info("Переключення на колекцію: {}", collection.getName());
        Collection activated = switchCollectionUseCase.execute(collection);
        appState.setCurrentLibraryCollection(activated);
        statisticsService.refreshStatistics();
        appState.getStatusBar().setStatistics(statisticsService.getStatistics());
        appState.getStatusBar().setStatusText("Переключено на колекцію: " + activated.getName());
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

    public void handleRenameCollection(Runnable onComplete) {
        Collection current = appState.getCurrentLibraryCollection();
        if (current == null) {
            dialogService.showWarning("Немає колекції", "Спочатку виберіть колекцію.");
            return;
        }
        Optional<String> result = dialogService.showTextInput(
                "Перейменування колекції",
                "Введіть нову назву для \"" + current.getName() + "\"",
                "Нова назва:",
                current.getName()
        );
        result.ifPresent(newName -> {
            if (!newName.isBlank() && !newName.equals(current.getName())) {
                try {
                    Collection renamed = renameCollectionUseCase.execute(current.getId(), newName);
                    appState.setCurrentLibraryCollection(renamed);
                    statisticsService.refreshStatistics();
                    appState.getStatusBar().setStatistics(statisticsService.getStatistics());
                    eventPublisher.publishEvent(new CollectionChangedEvent(renamed));
                    dialogService.showInfo("Успішно", "Колекцію перейменовано на \"" + newName + "\"");
                    if (onComplete != null) {
                        onComplete.run();
                    }
                } catch (Exception e) {
                    dialogService.showError("Помилка", "Не вдалося перейменувати: " + e.getMessage());
                }
            }
        });
    }

    public void handleDeleteCollection(Runnable onComplete) {
        Collection current = appState.getCurrentLibraryCollection();
        if (current == null) {
            dialogService.showWarning("Немає колекції", "Спочатку виберіть колекцію.");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Підтвердження");
        confirm.setHeaderText("Видалити колекцію \"" + current.getName() + "\"?");
        confirm.setContentText("Всі дані колекції будуть видалені без можливості відновлення.");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            try {
                deleteCollectionUseCase.execute(current.getId());
                appState.setCurrentLibraryCollection(null);
                eventPublisher.publishEvent(new NavigationRefreshEvent());
                dialogService.showInfo("Успішно", "Колекцію видалено");
                statisticsService.refreshStatistics();
                appState.getStatusBar().setStatistics(statisticsService.getStatistics());
                if (onComplete != null) {
                    onComplete.run();
                }
            } catch (Exception e) {
                dialogService.showError("Помилка", "Не вдалося видалити: " + e.getMessage());
            }
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