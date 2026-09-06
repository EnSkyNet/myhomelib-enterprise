package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.service.DatabaseToolsService;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
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

            Stage stage = new Stage();
            stage.setTitle("Перевірка цілісності");
            stage.setScene(new Scene(root, 720, 580));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(owner);
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття діалогу перевірки цілісності", e);
            dialogService.showError("Помилка", "Не вдалося відкрити діалог: " + e.getMessage());
        }
    }

    @FXML
    public void handleVacuum() {
        Collection collection = appState.getCurrentLibraryCollection();
        if (collection == null) {
            dialogService.showWarning("Немає колекції", "Спочатку виберіть колекцію.");
            return;
        }

        if (!dialogService.showConfirmation(
                "Оптимізація бази даних",
                "Виконати VACUUM для колекції \"" + collection.getName() + "\"?",
                "Це може зайняти деякий час. База даних буде оптимізована.")) {
            return;
        }

        appState.getStatusBar().setStatusText("Оптимізація бази даних...");
        appState.getStatusBar().setProgressVisible(true);

        String operationId = operationCenter.start(
                "VACUUM — " + collection.getName(), collection.getId(), OperationStage.OPTIMIZING_DATABASE, false);
        executor.submit(() -> {
                    databaseToolsService.vacuumCurrent();
                    return null;
                })
                .whenComplete((ignored, error) -> UiExecutor.runOnUiThread(() -> {
                    appState.getStatusBar().setProgressVisible(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        operationCenter.fail(operationId, cause);
                        log.error("Помилка оптимізації бази даних", cause);
                        appState.getStatusBar().setStatusText("Помилка оптимізації");
                        dialogService.showError("Помилка", "Не вдалося оптимізувати: " + cause.getMessage());
                    } else {
                        operationCenter.complete(operationId, "VACUUM завершено");
                        appState.getStatusBar().setStatusText("База даних оптимізована");
                        dialogService.showInfo("Успішно", "База даних оптимізована.");
                    }
                }));
    }

    @FXML
    public void handleRebuildIndex() {
        if (!dialogService.showConfirmation(
                "Перебудова індексу",
                "Ви впевнені, що хочете перебудувати пошуковий індекс?",
                "Це може зайняти деякий час для великих бібліотек.")) {
            return;
        }

        appState.getStatusBar().setStatusText("Перебудова індексу (фоновий режим)...");
        appState.getStatusBar().setProgressVisible(true);

        Collection collection = appState.getCurrentLibraryCollection();
        String operationId = operationCenter.start(
                "Перебудова Lucene", collection == null ? "" : collection.getId(),
                OperationStage.UPDATING_SEARCH_INDEX, false);
        databaseToolsService.rebuildIndexAsync()
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    appState.getStatusBar().setProgressVisible(false);
                    if (error != null) {
                        Throwable cause = UiExceptionSupport.unwrapAsync(error);
                        operationCenter.fail(operationId, cause);
                        appState.getStatusBar().setStatusText("Помилка перебудови індексу");
                        dialogService.showError("Помилка", "Не вдалося перебудувати індекс: " + cause.getMessage());
                    } else {
                        int count = databaseToolsService.getIndexedDocumentCount();
                        operationCenter.complete(operationId, "Проіндексовано " + count + " книг");
                        appState.getStatusBar().setStatusText("Індекс перебудовано. Проіндексовано " + count + " книг.");
                        dialogService.showInfo("Успішно",
                                "Пошуковий індекс перебудовано.\nПроіндексовано " + count + " книг.");
                    }
                }));
    }

    @FXML
    public void handleBackup(Stage owner) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/view/backup-dialog.fxml"));
            fxmlLoaderFactory.configureControllerFactory(loader);
            Parent root = loader.load();

            BackupController controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle("Резервне копіювання");
            stage.setScene(new Scene(root, 620, 520));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(owner);

            controller.setStage(stage);
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття діалогу резервного копіювання", e);
            dialogService.showError("Помилка", "Не вдалося відкрити діалог: " + e.getMessage());
        }
    }

    @FXML
    public void handleRestore(Stage owner) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/view/restore-dialog.fxml"));
            fxmlLoaderFactory.configureControllerFactory(loader);
            Parent root = loader.load();

            RestoreController controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle("Відновлення з резервної копії");
            stage.setScene(new Scene(root, 620, 450));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(owner);

            controller.setStage(stage);
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття діалогу відновлення", e);
            dialogService.showError("Помилка", "Не вдалося відкрити діалог: " + e.getMessage());
        }
    }

    @FXML
    public void handleStatistics(Stage owner) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/view/statistics.fxml"));
            fxmlLoaderFactory.configureControllerFactory(loader);
            Parent root = loader.load();
            StatisticsController controller = loader.getController();

            Stage stage = new Stage();
            stage.setTitle("Статистика колекції");
            stage.setScene(new Scene(root, 600, 400));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(owner);
            stage.setOnHidden(event -> controller.dispose());
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття статистики", e);
            dialogService.showError("Помилка", "Не вдалося відкрити статистику: " + e.getMessage());
        }
    }
}