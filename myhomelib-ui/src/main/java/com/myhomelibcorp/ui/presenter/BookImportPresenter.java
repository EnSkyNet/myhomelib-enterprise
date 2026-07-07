package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.usecase.imports.ImportDirectoryUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    private final ImportFileUseCase importFileUseCase;
    private final ImportDirectoryUseCase importDirectoryUseCase;
    private final BackgroundExecutor backgroundExecutor;
    private final StatusBarPresenter statusBarPresenter;
    private final ProgressPresenter progressPresenter;
    private final FileChooserService fileChooserService;

    public void importFb2() {
        Stage stage = new Stage();
        File file = fileChooserService.chooseFile(stage, "Виберіть FB2 файл",
                List.of(new javafx.stage.FileChooser.ExtensionFilter("FB2 файли", "*.fb2", "*.fbd")));
        if (file != null) {
            importFile(file.toPath());
        }
    }

    public void importInpx() {
        Stage stage = new Stage();
        File file = fileChooserService.chooseFile(stage, "Виберіть INPX файл",
                List.of(new javafx.stage.FileChooser.ExtensionFilter("INPX файли", "*.inpx", "*.inp")));
        if (file != null) {
            importFile(file.toPath());
        }
    }

    public void importDirectory(Path directory, Runnable onComplete) {
        statusBarPresenter.setStatus("Імпорт каталогу: " + directory.getFileName());
        progressPresenter.showProgress(true);
        AtomicBoolean cancelFlag = new AtomicBoolean(false);
        DoubleConsumer progressConsumer = progress -> UiExecutor.runOnUiThread(() ->
                progressPresenter.setProgress(progress));

        backgroundExecutor.submit(() -> importDirectoryUseCase.execute(directory, progressConsumer, cancelFlag))
                .thenAccept(count -> UiExecutor.runOnUiThread(() -> {
                    progressPresenter.hideProgress();
                    statusBarPresenter.setStatus("Імпорт завершено. Додано " + count + " книг");
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        progressPresenter.hideProgress();
                        statusBarPresenter.setStatus("Помилка імпорту: " + ex.getMessage());
                    });
                    log.error("Directory import failed", ex);
                    return null;
                });
    }

    // ---- Методи для зворотної сумісності ----
    public void importDirectory(Path directory) {
        importDirectory(directory, null);
    }

    public void importFile(Path file) {
        importFile(file, null);
    }

    public void importFile(Path file, Runnable onComplete) {
        statusBarPresenter.setStatus("Імпорт файлу: " + file.getFileName());
        progressPresenter.showProgress(true);
        backgroundExecutor.submit(() -> importFileUseCase.execute(file))
                .thenAccept(count -> UiExecutor.runOnUiThread(() -> {
                    progressPresenter.hideProgress();
                    statusBarPresenter.setStatus("Імпорт завершено. Додано " + count + " книг");
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }))
                .exceptionally(ex -> {
                    UiExecutor.runOnUiThread(() -> {
                        progressPresenter.hideProgress();
                        statusBarPresenter.setStatus("Помилка імпорту: " + ex.getMessage());
                    });
                    log.error("File import failed", ex);
                    return null;
                });
    }
}