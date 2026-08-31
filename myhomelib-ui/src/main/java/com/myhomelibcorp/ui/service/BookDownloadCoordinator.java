package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.catalog.LegacyOnlineBookLocation;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.download.DownloadBookUseCase;
import com.myhomelibcorp.application.usecase.download.RemoveLocalBookCopyUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
    private final ApplicationEventPublisher eventPublisher;
    private final Semaphore downloadSlots;
    private final Map<String, AtomicBoolean> active = new ConcurrentHashMap<>();

    public BookDownloadCoordinator(DownloadBookUseCase downloadBookUseCase, RemoveLocalBookCopyUseCase removeLocalBookCopyUseCase,
                                   BookResourcePort bookResourcePort, UiBackgroundExecutor executor, ApplicationState applicationState,
                                   DialogService dialogService, ApplicationSettingsPort settings, ApplicationEventPublisher eventPublisher) {
        this.downloadBookUseCase = downloadBookUseCase;
        this.removeLocalBookCopyUseCase = removeLocalBookCopyUseCase;
        this.bookResourcePort = bookResourcePort;
        this.executor = executor;
        this.applicationState = applicationState;
        this.dialogService = dialogService;
        this.eventPublisher = eventPublisher;
        int permits = Math.max(1, Math.min(16, settings.getInt("online.maxParallelDownloads", 2)));
        this.downloadSlots = new Semaphore(permits, true);
    }

    public CompletableFuture<Path> ensureLocal(BookDto book) {
        return download(book, false);
    }

    /**
     * Opening a remote-only book is an explicit user decision. A missing physical
     * file must never start a download silently just because the Reader was opened.
     */
    public CompletableFuture<Path> ensureLocalForOpen(BookDto book) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        normalizeLegacyRemoteStorage(book);
        var existing = bookResourcePort.locateBookFile(
                book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry());
        if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get());

        if (!Platform.isFxApplicationThread()) {
            CompletableFuture<Path> result = new CompletableFuture<>();
            Platform.runLater(() -> ensureLocalForOpen(book).whenComplete((path, error) -> {
                if (error == null) result.complete(path);
                else result.completeExceptionally(error);
            }));
            return result;
        }

        boolean approved = dialogService.showConfirmation(
                "Книга відсутня",
                book.getTitle() == null || book.getTitle().isBlank() ? "Файл книги відсутній" : book.getTitle(),
                "Книга фізично відсутня на комп’ютері. Завантажити та зберегти її перед відкриттям?");
        if (!approved) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                    "Відкриття скасовано користувачем"));
        }
        return download(book, false);
    }

    /** Force a fresh online copy even when an older local file exists. */
    public CompletableFuture<Path> downloadUpdate(BookDto book) {
        return download(book, true);
    }

    private CompletableFuture<Path> download(BookDto book, boolean force) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        normalizeLegacyRemoteStorage(book);
        if (!force) {
            var existing = bookResourcePort.locateBookFile(book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry());
            if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get());
        }

        Collection collection = applicationState.getCurrentLibraryCollection();
        if (collection == null) {
            return failedVisible("Файл відсутній локально, але активну online-колекцію не визначено");
        }
        if ((collection.getUrl() == null || collection.getUrl().isBlank())
                && (collection.getConnectionScript() == null || collection.getConnectionScript().isBlank())) {
            return failedVisible("Файл відсутній локально, а URL/ConnectionScript online-колекції не налаштовано");
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
                        while (!cancel.get() && !Thread.currentThread().isInterrupted()) {
                            if (downloadSlots.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                                acquired = true;
                                break;
                            }
                        }
                        if (!acquired || cancel.get() || Thread.currentThread().isInterrupted()) {
                            throw new java.util.concurrent.CancellationException("Завантаження скасовано");
                        }
                        return downloadBookUseCase.execute(book, collection, cancel, value ->
                                Platform.runLater(() -> applicationState.getStatusBar().setProgress(value)), force);
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
                            eventPublisher.publishEvent(new NavigationRefreshEvent());
                        } else {
                            Throwable cause = unwrap(error);
                            applicationState.getStatusBar().setStatusText("Помилка завантаження");
                            String message = cause.getMessage() == null ? cause.toString() : cause.getMessage();
                            if (!message.toLowerCase().contains("скасовано")) dialogService.showError("Завантаження", message);
                        }
                    });
                });
    }

    /**
     * Repairs only the selected remote DTO. Earlier v7.1 builds persisted both the temporary
     * catalog cache as collection_root and the catalog member name (online.zip/extra.zip) as
     * the physical archive. Upstream MyHomeLib instead generates one FB2 ZIP path per book.
     * Successful download persists the corrected storage through DownloadBookUseCase.updateStorage().
     */
    private void normalizeLegacyRemoteStorage(BookDto book) {
        if (book == null) return;
        Collection collection = applicationState.getCurrentLibraryCollection();
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) return;

        if (isTransientCatalogRoot(book.getCollectionRoot())) {
            Path root = collection.getRootFolder() != null
                    ? collection.getRootFolder().toAbsolutePath().normalize()
                    : Path.of(System.getProperty("user.home"), ".myhomelibcorp", "downloads", collection.getId())
                        .toAbsolutePath().normalize();
            book.setCollectionRoot(root.toString());
            log.debug("Нормалізовано transient collection_root для remote-книги {} -> {}", book.getId(), root);
        }

        if (!book.isLocal() && isOnlineCollection(collection) && isFb2(book)) {
            String author = primaryAuthorName(book);
            String expectedFolder = LegacyOnlineBookLocation.archivePath(
                    author, book.getTitle(), book.getLibId(), book.getFileName());
            if (!expectedFolder.equals(book.getFolder())) {
                log.debug("Нормалізовано online archive для книги {}: {} -> {}",
                        book.getId(), book.getFolder(), expectedFolder);
                book.setFolder(expectedFolder);
                book.setArchiveEntry(book.getFileName());
            }
        }
    }

    private static boolean isOnlineCollection(Collection collection) {
        return collection != null && ((collection.getUrl() != null && !collection.getUrl().isBlank())
                || (collection.getConnectionScript() != null && !collection.getConnectionScript().isBlank()));
    }

    private static boolean isFb2(BookDto book) {
        if (book == null || book.getFileName() == null) return false;
        return book.getFileName().toLowerCase(java.util.Locale.ROOT).endsWith(".fb2");
    }

    private static String primaryAuthorName(BookDto book) {
        if (book != null && !book.getAuthors().isEmpty()) {
            var a = book.getAuthors().get(0);
            if (a.getFullName() != null && !a.getFullName().isBlank()) return a.getFullName();
            return java.util.stream.Stream.of(a.getLastName(), a.getFirstName(), a.getMiddleName())
                    .filter(v -> v != null && !v.isBlank()).collect(java.util.stream.Collectors.joining(" "));
        }
        String text = book == null ? null : book.getAuthorsText();
        if (text == null || text.isBlank()) return "Невідомий Автор";
        int comma = text.indexOf(',');
        return (comma > 0 ? text.substring(0, comma) : text).trim();
    }

    private static boolean isTransientCatalogRoot(String root) {
        if (root == null || root.isBlank()) return false;
        String normalized = root.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("/.myhomelibcorp/cache/catalog-updates")
                || normalized.endsWith("/.myhomelibcorp/cache/catalog-updates");
    }

    private CompletableFuture<Path> failedVisible(String message) {
        Platform.runLater(() -> dialogService.showError("Завантаження", message));
        return CompletableFuture.failedFuture(new IllegalStateException(message));
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
