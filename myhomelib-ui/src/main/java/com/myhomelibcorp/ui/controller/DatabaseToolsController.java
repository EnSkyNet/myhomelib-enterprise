package com.myhomelibcorp.ui.controller;


import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.service.DatabaseToolsService;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseToolsController {

    private final FxmlLoaderFactory fxmlLoaderFactory;
    private final LocalizationService i18n;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final DatabaseToolsService databaseToolsService;
    private final UiBackgroundExecutor executor;
    private final OperationCenterService operationCenter;

    @FXML
    public void handleCheckIntegrity(Stage owner) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/view/integrity-check.fxml"));
            fxmlLoaderFactory.configureControllerFactory(loader);
            Parent root = loader.load();
            i18n.apply(root);

            Stage stage = new Stage();
            stage.setTitle(UiText.text("ui.fxml.integrity_check.library_health"));
            stage.setScene(new Scene(root, 960, 740));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(owner);
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття діалогу перевірки цілісності", e);
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.database_tools_controller.acb44428f0") + UiExceptionSupport.message(e));
        }
    }

    @FXML
    public void handleVacuum() {
        Collection collection = appState.getCurrentLibraryCollection();
        if (collection == null) {
            dialogService.showWarning(UiText.text("ui.java.database_tools_controller.2032bb9afc"), UiText.text("ui.java.database_tools_controller.6686ed3c16"));
            return;
        }

        if (!dialogService.showConfirmation(
                UiText.text("ui.java.database_tools_controller.bd69af2a4d"),
                UiText.text("ui.java.database_tools_controller.33ed04b762") + collection.getName() + "\"?",
                UiText.text("ui.java.database_tools_controller.68958c4a0d"))) {
            return;
        }

        appState.getStatusBar().setStatusText(UiText.text("ui.java.database_tools_controller.e253627eba"));
        appState.getStatusBar().setProgressVisible(true);

        String operationId = operationCenter.start(
                UiText.text("ui.database.operation.vacuum") + collection.getName(), collection.getId(), OperationStage.OPTIMIZING_DATABASE, false);
        String expectedCollectionId = collection.getId();
        executor.submit(() -> {
                    databaseToolsService.vacuumCurrent(expectedCollectionId);
                    return null;
                })
                .whenComplete((ignored, error) -> {
                    Throwable cause = error == null ? null : UiExceptionSupport.unwrapAsync(error);
                    if (cause != null) {
                        operationCenter.fail(operationId, cause);
                        log.error("Помилка оптимізації бази даних", cause);
                    } else {
                        operationCenter.complete(operationId, UiText.text("ui.java.database_tools_controller.27c663f71f"));
                    }
                    UiExecutor.runOnUiThread(() -> {
                        if (!isCurrentCollection(expectedCollectionId)) {
                            log.debug("Пропущено stale VACUUM UI callback для колекції {}", expectedCollectionId);
                            return;
                        }
                        appState.getStatusBar().setProgressVisible(false);
                        if (cause != null) {
                            appState.getStatusBar().setStatusText(UiText.text("ui.java.database_tools_controller.54aea21f05"));
                            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.database_tools_controller.16eb4308b4") + UiExceptionSupport.message(cause));
                        } else {
                            appState.getStatusBar().setStatusText(UiText.text("ui.java.database_tools_controller.23711c3223"));
                            dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.database_tools_controller.fdafb24283"));
                        }
                    });
                });
    }

    @FXML
    public void handleRebuildIndex() {
        if (!dialogService.showConfirmation(
                UiText.text("ui.java.database_tools_controller.8cd525554e"),
                UiText.text("ui.java.database_tools_controller.0d465679d3"),
                UiText.text("ui.java.database_tools_controller.5a498bad62"))) {
            return;
        }

        Collection collection = appState.getCurrentLibraryCollection();
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) {
            dialogService.showWarning(UiText.text("ui.java.database_tools_controller.2032bb9afc"),
                    UiText.text("ui.java.database_tools_controller.6686ed3c16"));
            return;
        }

        appState.getStatusBar().setStatusText(UiText.text("ui.java.database_tools_controller.b5f8b6c453"));
        appState.getStatusBar().setProgressVisible(true);

        String expectedCollectionId = collection.getId();
        String operationId = operationCenter.start(
                UiText.text("ui.java.database_tools_controller.5a1503fdd4"), expectedCollectionId,
                OperationStage.UPDATING_SEARCH_INDEX, false);
        databaseToolsService.rebuildIndexAsync()
                .whenComplete((result, error) -> {
                    Throwable cause = error == null ? null : UiExceptionSupport.unwrapAsync(error);
                    Integer count = null;
                    if (cause == null && isCurrentCollection(expectedCollectionId)) {
                        try {
                            count = databaseToolsService.getIndexedDocumentCount(expectedCollectionId);
                        } catch (RuntimeException staleResult) {
                            log.info("Index rebuild completed, but its count is no longer displayable for collection {}: {}",
                                    expectedCollectionId, staleResult.getMessage());
                        }
                    }
                    if (cause != null) {
                        operationCenter.fail(operationId, cause);
                    } else {
                        String detail = count == null
                                ? UiText.text("ui.database.index_rebuilt")
                                : UiText.text("ui.java.database_tools_controller.e6d0d15525") + count + UiText.text("ui.java.database_tools_controller.faf0c7e42c");
                        operationCenter.complete(operationId, detail);
                    }
                    Integer finalCount = count;
                    UiExecutor.runOnUiThread(() -> {
                        if (!isCurrentCollection(expectedCollectionId)) {
                            log.debug("Пропущено stale index-rebuild UI callback для колекції {}", expectedCollectionId);
                            return;
                        }
                        appState.getStatusBar().setProgressVisible(false);
                        if (cause != null) {
                            appState.getStatusBar().setStatusText(UiText.text("ui.java.database_tools_controller.c86922def7"));
                            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.database_tools_controller.4856ebd467") + UiExceptionSupport.message(cause));
                        } else if (finalCount == null) {
                            String completed = UiText.text("ui.database.index_rebuilt");
                            appState.getStatusBar().setStatusText(completed);
                            dialogService.showInfo(UiText.text("common.success"), completed);
                        } else {
                            appState.getStatusBar().setStatusText(UiText.text("ui.java.database_tools_controller.439b61118b") + finalCount + UiText.text("ui.java.database_tools_controller.af17e2b02c"));
                            dialogService.showInfo(UiText.text("common.success"),
                                    UiText.text("ui.java.database_tools_controller.e62401de7e") + finalCount + UiText.text("ui.java.database_tools_controller.af17e2b02c"));
                        }
                    });
                });
    }

    private boolean isCurrentCollection(String expectedCollectionId) {
        return expectedCollectionId != null
                && !expectedCollectionId.isBlank()
                && expectedCollectionId.equals(appState.getCurrentLibraryCollectionId());
    }

    @FXML
    public void handleBackup(Stage owner) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/view/backup-dialog.fxml"));
            fxmlLoaderFactory.configureControllerFactory(loader);
            Parent root = loader.load();
            i18n.apply(root);

            BackupController controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle(UiText.text("ui.backup.confirm.start.title"));
            stage.setScene(new Scene(root, 620, 520));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(owner);

            controller.setStage(stage);
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття діалогу резервного копіювання", e);
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.database_tools_controller.acb44428f0") + UiExceptionSupport.message(e));
        }
    }

    @FXML
    public void handleRestore(Stage owner) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/view/restore-dialog.fxml"));
            fxmlLoaderFactory.configureControllerFactory(loader);
            Parent root = loader.load();
            i18n.apply(root);

            RestoreController controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle(UiText.text("ui.java.database_tools_controller.1cc5a302ca"));
            stage.setScene(new Scene(root, 620, 450));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(owner);

            controller.setStage(stage);
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття діалогу відновлення", e);
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.database_tools_controller.acb44428f0") + UiExceptionSupport.message(e));
        }
    }

    @FXML
    public void handleStatistics(Stage owner) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/view/statistics.fxml"));
            fxmlLoaderFactory.configureControllerFactory(loader);
            Parent root = loader.load();
            i18n.apply(root);
            StatisticsController controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle(UiText.text("ui.java.database_tools_controller.75ac49c498"));
            stage.setScene(new Scene(root, 600, 400));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(owner);
            stage.setOnHidden(event -> controller.dispose());
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття статистики", e);
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.database_tools_controller.7db23c2ef2") + UiExceptionSupport.message(e));
        }
    }
}
