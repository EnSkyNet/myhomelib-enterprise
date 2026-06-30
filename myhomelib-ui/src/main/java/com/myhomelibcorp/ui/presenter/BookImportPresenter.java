package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.usecase.imports.ImportDirectoryUseCase;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.ui.service.BackgroundExecutor;
import com.myhomelibcorp.ui.util.UiExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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

    public void importFile(Path file, Runnable onComplete) {
        statusBarPresenter.setStatus("Імпорт файлу: " + file.getFileName());
        progressPresenter.showProgress(true);
        backgroundExecutor.submit(() -> importFileUseCase.execute(file))
                .thenAccept(count -> UiExecutor.runOnUiThread(() -> {
                    progressPresenter.hideProgress();
                    statusBarPresenter.setStatus("Імпорт завершено. Додано " + count + " книг");
                    if (onComplete != null) onComplete.run();
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
                    if (onComplete != null) onComplete.run();
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
}