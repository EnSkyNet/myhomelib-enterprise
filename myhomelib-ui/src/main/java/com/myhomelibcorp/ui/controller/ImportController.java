package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.usecase.sync.SyncFolderUseCase;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.domain.model.sync.SyncOptions;
import com.myhomelibcorp.domain.model.sync.SyncResult;
import com.myhomelibcorp.ui.presenter.BookImportPresenter;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.CompletableFuture;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportController {

    private final BookImportPresenter bookImportPresenter;
    private final SyncFolderUseCase syncFolderUseCase;
    private final DialogService dialogService;
    private final FileChooserService fileChooserService;
    private final ApplicationState appState;
    private final OperationCenterService operationCenter;

    public void importFb2(Runnable onComplete) {
        bookImportPresenter.importFb2(onComplete);
    }

    public void importInpx(Runnable onComplete) {
        bookImportPresenter.importInpx(onComplete);
    }

    public void importDirectory(Runnable onComplete) {
        Stage stage = new Stage();
        File dir = fileChooserService.chooseDirectory(stage, "Виберіть папку з книгами");
        if (dir != null) {
            bookImportPresenter.importDirectory(dir.toPath(), onComplete);
        }
    }

    public void handleSyncFolder(Runnable onComplete) {
        Stage stage = new Stage();
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Виберіть папку для синхронізації");
        File dir = chooser.showDialog(stage);

        if (dir == null) {
            return;
        }

        Dialog<SyncOptions> dialog = createSyncOptionsDialog();
        dialog.showAndWait().ifPresent(options -> {
            appState.getStatusBar().setStatusText("🔄 Синхронізація папки: " + dir.getName());
            appState.getStatusBar().setProgressVisible(true);

            var collection = appState.getCurrentLibraryCollection();
            String operationId = operationCenter.start(
                    "Синхронізація папки — " + dir.getName(),
                    collection == null ? "" : collection.getId(), OperationStage.SYNCHRONIZING_FILES, false);
            CompletableFuture<SyncResult> future = syncFolderUseCase.executeAsync(dir.toPath(), options);

            future.thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                        operationCenter.complete(operationId, result.getSummary());
                        appState.getStatusBar().setProgressVisible(false);
                        appState.getStatusBar().setStatusText("✅ " + result.getSummary());
                        dialogService.showInfo("Синхронізація завершена", result.getSummary());
                        if (onComplete != null) onComplete.run();
                    }))
                    .exceptionally(ex -> {
                        Throwable cause = UiExceptionSupport.unwrapAsync(ex);
                        operationCenter.fail(operationId, cause);
                        UiExecutor.runOnUiThread(() -> {
                            appState.getStatusBar().setProgressVisible(false);
                            appState.getStatusBar().setStatusText("❌ Помилка синхронізації");
                            dialogService.showError("Помилка", "Не вдалося синхронізувати: " + cause.getMessage());
                        });
                        log.error("Sync failed", cause);
                        return null;
                    });
        });
    }

    private Dialog<SyncOptions> createSyncOptionsDialog() {
        Dialog<SyncOptions> dialog = new Dialog<>();
        dialog.setTitle("Налаштування синхронізації");
        dialog.setHeaderText("Виберіть опції синхронізації");

        ButtonType syncButton = new ButtonType("Синхронізувати", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(syncButton, ButtonType.CANCEL);

        VBox content = new VBox(10);
        content.setPadding(new javafx.geometry.Insets(20));

        CheckBox deleteOrphans = new CheckBox("Видаляти зайві книги");
        deleteOrphans.setSelected(false);

        CheckBox updateChanged = new CheckBox("Оновлювати змінені файли");
        updateChanged.setSelected(true);

        CheckBox processArchives = new CheckBox("Обробляти архіви (ZIP, 7z, RAR, CBZ)");
        processArchives.setSelected(true);

        content.getChildren().addAll(
                new Label("Опції синхронізації:"),
                deleteOrphans,
                updateChanged,
                processArchives
        );

        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == syncButton) {
                return SyncOptions.builder()
                        .deleteOrphans(deleteOrphans.isSelected())
                        .updateChanged(updateChanged.isSelected())
                        .processArchives(processArchives.isSelected())
                        .build();
            }
            return null;
        });

        return dialog;
    }
}