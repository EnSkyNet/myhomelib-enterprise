package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.download.DownloadBookUseCase;
import com.myhomelibcorp.application.usecase.download.RemoveLocalBookCopyUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class BookDownloadCoordinator {
    private final DownloadBookUseCase downloadBookUseCase;
    private final RemoveLocalBookCopyUseCase removeLocalBookCopyUseCase;
    private final BookResourcePort bookResourcePort;
    private final UiBackgroundExecutor executor;
    private final ApplicationState applicationState;
    private final DialogService dialogService;
    private final Semaphore downloadSlots;
    private final Map<String, AtomicBoolean> active = new ConcurrentHashMap<>();

    public BookDownloadCoordinator(DownloadBookUseCase downloadBookUseCase, RemoveLocalBookCopyUseCase removeLocalBookCopyUseCase,
                                   BookResourcePort bookResourcePort, UiBackgroundExecutor executor, ApplicationState applicationState,
                                   DialogService dialogService, ApplicationSettingsPort settings) {
        this.downloadBookUseCase = downloadBookUseCase;
        this.removeLocalBookCopyUseCase = removeLocalBookCopyUseCase;
        this.bookResourcePort = bookResourcePort;
        this.executor = executor;
        this.applicationState = applicationState;
        this.dialogService = dialogService;
        int permits = Math.max(1, Math.min(16, settings.getInt("online.maxParallelDownloads", 2)));
        this.downloadSlots = new Semaphore(permits, true);
    }

    public CompletableFuture<Path> ensureLocal(BookDto book) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        var existing = bookResourcePort.locateBookFile(book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry());
        if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get());

        Collection collection = applicationState.getCurrentLibraryCollection();
        if (collection == null || collection.getUrl() == null || collection.getUrl().isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Файл відсутній локально, а URL online-колекції не налаштовано"));
        }
        AtomicBoolean cancel = new AtomicBoolean(false);
        AtomicBoolean previous = active.putIfAbsent(book.getId(), cancel);
        if (previous != null) return CompletableFuture.failedFuture(new IllegalStateException("Завантаження цієї книги вже виконується"));

        Platform.runLater(() -> {
            applicationState.getStatusBar().setStatusText("Завантаження: " + book.getTitle());
            applicationState.getStatusBar().setProgressVisible(true);
            applicationState.getStatusBar().setProgress(0);
        });

        return executor.submit(() -> {
                    boolean acquired = false;
                    try {
                        downloadSlots.acquire();
                        acquired = true;
                        if (cancel.get()) throw new java.util.concurrent.CancellationException("Завантаження скасовано");
                        return downloadBookUseCase.execute(book, collection, cancel, value ->
                                Platform.runLater(() -> applicationState.getStatusBar().setProgress(value)));
                    } finally {
                        if (acquired) downloadSlots.release();
                    }
                })
                .whenComplete((path, error) -> {
                    active.remove(book.getId(), cancel);
                    Platform.runLater(() -> {
                        applicationState.getStatusBar().setProgressVisible(false);
                        if (error == null) {
                            applicationState.getStatusBar().setStatusText("Завантажено: " + book.getTitle());
                            // DB was updated by the use case; align DTO with the collection root for immediate Reader use.
                            Path root = collection.getRootFolder() != null ? collection.getRootFolder().toAbsolutePath().normalize()
                                    : Path.of(System.getProperty("user.home"), ".myhomelibcorp", "downloads", collection.getId()).toAbsolutePath().normalize();
                            book.setCollectionRoot(root.toString());
                            book.setLocal(true);
                        } else {
                            Throwable cause = unwrap(error);
                            applicationState.getStatusBar().setStatusText("Помилка завантаження");
                            String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                            if (!message.toLowerCase().contains("скасовано")) dialogService.showError("Завантаження", message);
                        }
                    });
                });
    }


    public CompletableFuture<Integer> removeLocalCopy(BookDto book) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        if (isDownloading(book)) return CompletableFuture.failedFuture(new IllegalStateException("Спочатку скасуйте активне завантаження"));
        return executor.submit(() -> removeLocalBookCopyUseCase.execute(book))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error == null) {
                        book.setLocal(false);
                        applicationState.getStatusBar().setStatusText("Локальну копію видалено: " + book.getTitle());
                    } else {
                        Throwable cause = unwrap(error);
                        dialogService.showError("Локальна копія", cause.getMessage() == null ? cause.toString() : cause.getMessage());
                    }
                }));
    }

    public boolean cancel(BookDto book) {
        if (book == null) return false;
        AtomicBoolean flag = active.get(book.getId());
        if (flag == null) return false;
        flag.set(true);
        applicationState.getStatusBar().setStatusText("Скасування завантаження…");
        return true;
    }

    public boolean isDownloading(BookDto book) {
        return book != null && active.containsKey(book.getId());
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException || current instanceof java.util.concurrent.ExecutionException)) current = current.getCause();
        return current;
    }
}
