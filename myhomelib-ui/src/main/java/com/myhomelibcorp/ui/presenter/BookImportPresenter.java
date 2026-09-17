package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.operation.LibraryOperationAvailabilityGuard;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.usecase.imports.ImportDirectoryUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.imports.ImportFileChooserFilters;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookImportPresenter {

    private final LocalizationService localizationService;
    private final ImportFileUseCase importFileUseCase;
    private final ImportDirectoryUseCase importDirectoryUseCase;
    private final UiBackgroundExecutor executor;
    private final FileChooserService fileChooserService;
    private final ApplicationState appState;
    private final LibraryOperationAvailabilityGuard operationAvailabilityGuard;
    private final OperationCenterService operationCenter;

    @Value("${app.import.batch-size:1000}")
    private int defaultBatchSize;

    public void importFb2() {
        importFb2(null);
    }

    public void importFb2(Runnable onComplete) {
        if (!operationAvailabilityGuard.ensureAvailable(localizationService.text("ui.import.action.book"))) return;
        Stage stage = new Stage();
        File file = fileChooserService.chooseFile(stage, localizationService.text("ui.import.choose_book_or_archive.title"), ImportFileChooserFilters.booksAndArchives(localizationService));
        if (file != null) {
            importFile(file.toPath(), onComplete);
        }
    }

    public void importInpx() {
        importInpx(null);
    }

    public void importInpx(Runnable onComplete) {
        importInpxInternal(onComplete, false);
    }

    public void updateCollectionFromInpx(Runnable onComplete) {
        importInpxInternal(onComplete, true);
    }

    private void importInpxInternal(Runnable onComplete, boolean collectionUpdate) {
        String action = localizationService.text(collectionUpdate
                ? "ui.import.action.collection_update" : "ui.import.action.catalog");
        if (!operationAvailabilityGuard.ensureAvailable(action)) return;
        Stage stage = new Stage();
        File file = fileChooserService.chooseFile(stage, localizationService.text("ui.import.choose_inpx.title"), ImportFileChooserFilters.catalogs(localizationService));
        if (file != null) {
            importFile(file.toPath(), onComplete, collectionUpdate);
        }
    }

    public void importDirectory(Path directory, Runnable onComplete) {
        if (!operationAvailabilityGuard.ensureAvailable(localizationService.text("ui.import.action.directory"))) return;
        var statusBar = appState.getStatusBar();
        statusBar.setStatusText(localizationService.format("ui.import.presenter.directory_started", directory.getFileName()));
        statusBar.setProgressVisible(true);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        DoubleConsumer progressConsumer = progress -> UiExecutor.runOnUiThread(() ->
                statusBar.setProgress(progress));

        String operationTitle = localizationService.format("ui.import.operation.directory", directory.getFileName());
        String collectionId = currentCollectionId();
        String operationId = operationCenter.start(operationTitle, collectionId, OperationKind.CATALOG_IMPORT,
                OperationStage.IMPORTING, false);
        ImportContext context = ImportContext.builder()
                .rootDirectory(directory)
                .updateExisting(true)
                .batchSize(defaultBatchSize)
                .indexAfterSave(true)
                .operationId(operationId)
                .operationProgressListener(progress -> operationCenter.accept(
                        operationTitle, collectionId, OperationKind.CATALOG_IMPORT, progress))
                .progressListener(progressConsumer)
                .statusConsumer(status -> UiExecutor.runOnUiThread(() -> statusBar.setStatusText(status)))
                .cancelFlag(cancelFlag)
                .build();

        executor.submit(() -> importDirectoryUseCase.execute(context))
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    if (result.status() == ImportStatus.CANCELLED) {
                        operationCenter.cancel(operationId, localizationService.text("ui.import.result.directory_cancelled"));
                    } else {
                        operationCenter.complete(operationId, localizationService.format("ui.import.result.imported", result.imported())
                                + (result.errors() > 0 ? localizationService.format("ui.import.result.errors_suffix", result.errors()) : ""));
                    }
                    statusBar.setProgressVisible(false);
                    statusBar.setStatusText(localizationService.format("ui.import.presenter.directory_completed", result.imported()));
                    if (onComplete != null) onComplete.run();
                }))
                .exceptionally(ex -> {
                    Throwable cause = unwrap(ex);
                    operationCenter.fail(operationId, cause);
                    UiExecutor.runOnUiThread(() -> {
                        statusBar.setProgressVisible(false);
                        statusBar.setStatusText(localizationService.format("ui.import.presenter.error", cause.getMessage()));
                    });
                    log.error("Directory import failed", cause);
                    return null;
                });
    }

    public void importDirectory(Path directory) {
        importDirectory(directory, null);
    }

    public void importFile(Path file, Runnable onComplete) {
        importFile(file, onComplete, false);
    }

    private void importFile(Path file, Runnable onComplete, boolean collectionUpdate) {
        String requestedAction = localizationService.text(collectionUpdate
                ? "ui.import.action.collection_update" : "ui.import.action.generic");
        if (!operationAvailabilityGuard.ensureAvailable(requestedAction)) return;
        var statusBar = appState.getStatusBar();
        statusBar.setStatusText(localizationService.format("ui.import.presenter.file_started", file.getFileName()));
        statusBar.setProgressVisible(true);

        String fileName = file.getFileName() == null ? file.toString() : file.getFileName().toString();
        boolean catalogFile = fileName.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(inpx|inp)$");
        String operationTitle = collectionUpdate
                ? localizationService.format("ui.import.operation.collection_update", fileName)
                : catalogFile
                ? localizationService.format("ui.import.operation.catalog", fileName)
                : localizationService.format("ui.import.operation.book", fileName);
        OperationKind operationKind = collectionUpdate ? OperationKind.CATALOG_UPDATE : OperationKind.CATALOG_IMPORT;
        String collectionId = currentCollectionId();
        String operationId = operationCenter.start(operationTitle, collectionId, operationKind,
                OperationStage.IMPORTING, false);
        ImportContext context = ImportContext.builder()
                .file(file)
                .batchSize(defaultBatchSize)
                .indexAfterSave(true)
                .operationId(operationId)
                .operationProgressListener(progress -> operationCenter.accept(
                        operationTitle, collectionId, operationKind, progress))
                .statusConsumer(status -> UiExecutor.runOnUiThread(() -> statusBar.setStatusText(status)))
                .progressListener(progress -> UiExecutor.runOnUiThread(() -> statusBar.setProgress(progress)))
                .build();

        executor.submit(() -> importFileUseCase.execute(context))
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    if (result.status() == ImportStatus.CANCELLED) {
                        operationCenter.cancel(operationId, localizationService.text(collectionUpdate
                                ? "ui.import.result.collection_update_cancelled" : "ui.import.status.cancelled"));
                    } else {
                        String resultLabel = localizationService.format(collectionUpdate
                                ? "ui.import.result.processed" : "ui.import.result.imported", result.imported());
                        operationCenter.complete(operationId, resultLabel
                                + (result.errors() > 0 ? localizationService.format("ui.import.result.errors_suffix", result.errors()) : ""));
                    }
                    statusBar.setProgressVisible(false);
                    statusBar.setStatusText(collectionUpdate
                            ? localizationService.format("ui.import.result.collection_updated", result.imported())
                            : localizationService.format("ui.import.presenter.file_completed", result.imported()));
                    if (onComplete != null) onComplete.run();
                }))
                .exceptionally(ex -> {
                    Throwable cause = unwrap(ex);
                    operationCenter.fail(operationId, cause);
                    UiExecutor.runOnUiThread(() -> {
                        statusBar.setProgressVisible(false);
                        statusBar.setStatusText(localizationService.format("ui.import.presenter.error", cause.getMessage()));
                    });
                    log.error("File import failed", cause);
                    return null;
                });
    }

    public void importFile(Path file) {
        importFile(file, null);
    }

    private String currentCollectionId() {
        var collection = appState.getCurrentLibraryCollection();
        return collection == null || collection.getId() == null ? "" : collection.getId();
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? new IllegalStateException(localizationService.text("common.error.unknown")) : current;
    }

}
