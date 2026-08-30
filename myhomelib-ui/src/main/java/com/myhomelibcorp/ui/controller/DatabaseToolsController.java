package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.service.DatabaseToolsService;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.service.DialogService;
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
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DatabaseToolsController {

    private final ApplicationContext springContext;
    private final ApplicationState appState;
    private final DialogService dialogService;
    private final DatabaseToolsService databaseToolsService;
    private final CollectionLifecycleService collectionLifecycleService;

    @FXML
    public void handleCheckIntegrity(Stage owner) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    getClass().getResource("/view/integrity-check.fxml"));
            loader.setControllerFactory(springContext::getBean);
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

        new Thread(() -> {
            try {
                databaseToolsService.vacuumCurrent();

                UiExecutor.runOnUiThread(() -> {
                    appState.getStatusBar().setProgressVisible(false);
                    appState.getStatusBar().setStatusText("База даних оптимізована");
                    dialogService.showInfo("Успішно", "База даних оптимізована.");
                });
            } catch (Exception e) {
                log.error("Помилка оптимізації бази даних", e);
                UiExecutor.runOnUiThread(() -> {
                    appState.getStatusBar().setProgressVisible(false);
                    appState.getStatusBar().setStatusText("Помилка оптимізації");
                    dialogService.showError("Помилка", "Не вдалося оптимізувати: " + e.getMessage());
                });
            }
        }).start();
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

        // Використовуємо асинхронний метод
        collectionLifecycleService.rebuildSearchIndexAsync()
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    appState.getStatusBar().setProgressVisible(false);
                    if (error != null) {
                        appState.getStatusBar().setStatusText("Помилка перебудови індексу");
                        dialogService.showError("Помилка", "Не вдалося перебудувати індекс: " + error.getMessage());
                    } else {
                        int count = databaseToolsService.getIndexedDocumentCount();
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
            loader.setControllerFactory(springContext::getBean);
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
            loader.setControllerFactory(springContext::getBean);
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
            loader.setControllerFactory(springContext::getBean);
            Parent root = loader.load();

            Stage stage = new Stage();
            stage.setTitle("Статистика колекції");
            stage.setScene(new Scene(root, 600, 400));
            stage.initModality(Modality.WINDOW_MODAL);
            stage.initOwner(owner);
            stage.show();

        } catch (Exception e) {
            log.error("Помилка відкриття статистики", e);
            dialogService.showError("Помилка", "Не вдалося відкрити статистику: " + e.getMessage());
        }
    }
}