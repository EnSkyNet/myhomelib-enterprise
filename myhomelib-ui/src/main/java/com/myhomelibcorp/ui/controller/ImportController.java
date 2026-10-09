package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.service.DialogLocalization;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.usecase.sync.SyncFolderUseCase;
import com.myhomelibcorp.ui.operation.LibraryOperationAvailabilityGuard;
import com.myhomelibcorp.domain.model.sync.SyncOptions;
import com.myhomelibcorp.domain.model.sync.SyncResult;
import com.myhomelibcorp.ui.presenter.BookImportPresenter;
import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
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
    private final LibraryOperationAvailabilityGuard operationAvailabilityGuard;

    public void importFb2(Runnable onComplete) {
        bookImportPresenter.importFb2(onComplete);
    }

    public void importInpx(Runnable onComplete) {
        bookImportPresenter.importInpx(onComplete);
    }

    public void updateCollectionFromInpx(Runnable onComplete) {
        bookImportPresenter.updateCollectionFromInpx(onComplete);
    }

    public void importDirectory(Runnable onComplete) {
        Stage stage = new Stage();
        File dir = fileChooserService.chooseDirectory(stage, UiText.text("ui.fxml.import_workspace.choose_a_folder_with_books"));
        if (dir != null) {
            bookImportPresenter.importDirectory(dir.toPath(), onComplete);
        }
    }

    public void handleSyncFolder(Runnable onComplete) {
        if (!operationAvailabilityGuard.ensureAvailable(UiText.text("ui.java.import_controller.0297a4c09d"))) return;
        Stage stage = new Stage();
        File dir = fileChooserService.chooseDirectory(stage, UiText.text("ui.java.import_controller.644f7cf8ab"));

        if (dir == null) {
            return;
        }

        Dialog<SyncOptions> dialog = createSyncOptionsDialog();
        dialog.showAndWait().ifPresent(options -> {
            String expectedCollectionId = appState.getCurrentLibraryCollectionId();
            appState.getStatusBar().setStatusText(UiText.text("ui.java.import_controller.50b8f36135") + dir.getName());
            appState.getStatusBar().setProgressVisible(true);

            CompletableFuture<SyncResult> future = syncFolderUseCase.executeAsync(dir.toPath(), options);

            future.thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                        if (!java.util.Objects.equals(expectedCollectionId, appState.getCurrentLibraryCollectionId())) return;
                        appState.getStatusBar().setProgressVisible(false);
                        String summary = syncSummary(result);
                        appState.getStatusBar().setStatusText("✅ " + summary);
                        dialogService.showInfo(UiText.text("ui.java.import_controller.f1d826f533"), summary);
                        if (onComplete != null) onComplete.run();
                    }))
                    .exceptionally(ex -> {
                        Throwable cause = UiExceptionSupport.unwrapAsync(ex);
                        UiExecutor.runOnUiThread(() -> {
                            if (!java.util.Objects.equals(expectedCollectionId, appState.getCurrentLibraryCollectionId())) return;
                            appState.getStatusBar().setProgressVisible(false);
                            appState.getStatusBar().setStatusText(UiText.text("ui.java.import_controller.1ac52df2c6"));
                            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.import_controller.41fe14596b") + UiExceptionSupport.message(cause));
                        });
                        log.error("Sync failed", cause);
                        return null;
                    });
        });
    }

    private String syncSummary(SyncResult result) {
        if (result == null) return UiText.text("ui.sync.summary.empty");
        return UiText.format("ui.sync.summary", result.getAdded(), result.getUpdated(), result.getDeleted(),
                result.getSkipped(), result.getErrors(), syncDuration(result.getDuration()));
    }

    private String syncDuration(java.time.Duration duration) {
        long seconds = Math.max(0L, duration == null ? 0L : duration.getSeconds());
        if (seconds < 60) return UiText.format("common.duration.seconds", seconds);
        if (seconds < 3600) return UiText.format("common.duration.minutes_seconds", seconds / 60, seconds % 60);
        return UiText.format("common.duration.hours_minutes", seconds / 3600, (seconds % 3600) / 60);
    }

    private Dialog<SyncOptions> createSyncOptionsDialog() {
        Dialog<SyncOptions> dialog = new Dialog<>();
        DialogLocalization.prepare(dialog);
        dialog.setTitle(UiText.text("ui.java.import_controller.3ccd450366"));
        dialog.setHeaderText(UiText.text("ui.java.import_controller.3b33fd9c72"));

        ButtonType syncButton = new ButtonType(UiText.text("ui.java.import_controller.74ca1f0181"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(syncButton, ButtonType.CANCEL);

        VBox content = new VBox(10);
        content.setPadding(new javafx.geometry.Insets(20));

        CheckBox deleteOrphans = new CheckBox(UiText.text("ui.java.import_controller.8dc0aa0650"));
        deleteOrphans.setSelected(false);

        CheckBox updateChanged = new CheckBox(UiText.text("ui.java.import_controller.d4c57828a1"));
        updateChanged.setSelected(true);

        CheckBox processArchives = new CheckBox(UiText.text("ui.java.import_controller.6c4075be57"));
        processArchives.setSelected(true);

        content.getChildren().addAll(
                new Label(UiText.text("ui.java.import_controller.f4120e455c")),
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