package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.ui.service.LocalizationService;
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
import java.util.List;
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

    @Value("${app.import.batch-size:1000}")
    private int defaultBatchSize;

    public void importFb2() {
        importFb2(null);
    }

    public void importFb2(Runnable onComplete) {
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
        Stage stage = new Stage();
        File file = fileChooserService.chooseFile(stage, localizationService.text("ui.import.choose_inpx.title"), ImportFileChooserFilters.catalogs(localizationService));
        if (file != null) {
            importFile(file.toPath(), onComplete);
        }
    }

    public void importDirectory(Path directory, Runnable onComplete) {
        var statusBar = appState.getStatusBar();
        statusBar.setStatusText(localizationService.format("ui.import.presenter.directory_started", directory.getFileName()));
        statusBar.setProgressVisible(true);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        DoubleConsumer progressConsumer = progress -> UiExecutor.runOnUiThread(() ->
                statusBar.setProgress(progress));

        ImportContext context = ImportContext.builder()
                .rootDirectory(directory)
                .updateExisting(true)
                .batchSize(defaultBatchSize)
                .indexAfterSave(true)
                .progressListener(progressConsumer)
                .statusConsumer(status -> UiExecutor.runOnUiThread(() -> statusBar.setStatusText(status)))
                .cancelFlag(cancelFlag)
                .build();

        executor.submit(() -> importDirectoryUseCase.execute(context))
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    statusBar.setProgressVisible(false);
                    statusBar.setStatusText(localizationService.format("ui.import.presenter.directory_completed", result.imported()));
                    if (onComplete != null) onComplete.run();
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        statusBar.setProgressVisible(false);
                        statusBar.setStatusText(localizationService.format("ui.import.presenter.error", ex.getMessage()));
                    });
                    log.error("Directory import failed", ex);
                    return null;
                });
    }

    public void importDirectory(Path directory) {
        importDirectory(directory, null);
    }

    public void importFile(Path file, Runnable onComplete) {
        var statusBar = appState.getStatusBar();
        statusBar.setStatusText(localizationService.format("ui.import.presenter.file_started", file.getFileName()));
        statusBar.setProgressVisible(true);

        ImportContext context = ImportContext.builder()
                .file(file)
                .batchSize(defaultBatchSize)
                .indexAfterSave(true)
                .statusConsumer(status -> UiExecutor.runOnUiThread(() -> statusBar.setStatusText(status)))
                .progressListener(progress -> UiExecutor.runOnUiThread(() -> statusBar.setProgress(progress)))
                .build();

        executor.submit(() -> importFileUseCase.execute(context))
                .thenAccept(result -> UiExecutor.runOnUiThread(() -> {
                    statusBar.setProgressVisible(false);
                    statusBar.setStatusText(localizationService.format("ui.import.presenter.file_completed", result.imported()));
                    if (onComplete != null) onComplete.run();
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        statusBar.setProgressVisible(false);
                        statusBar.setStatusText(localizationService.format("ui.import.presenter.error", ex.getMessage()));
                    });
                    log.error("File import failed", ex);
                    return null;
                });
    }

    public void importFile(Path file) {
        importFile(file, null);
    }
}
