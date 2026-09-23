package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.catalog.LegacyOnlineBookLocation;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.download.DownloadBookUseCase;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.application.usecase.download.RemoveLocalBookCopyUseCase;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.ui.operation.OperationCenterService;
import com.myhomelibcorp.ui.operation.OperationKind;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.event.NavigationRefreshEvent;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
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
    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final BookResourcePort bookResourcePort;
    private final UiBackgroundExecutor executor;
    private final ApplicationState applicationState;
    private final DialogService dialogService;
    private final ApplicationEventPublisher eventPublisher;
    private final OperationCenterService operationCenter;
    private final Semaphore downloadSlots;
    private final Map<String, AtomicBoolean> active = new ConcurrentHashMap<>();

    public BookDownloadCoordinator(DownloadBookUseCase downloadBookUseCase, RemoveLocalBookCopyUseCase removeLocalBookCopyUseCase,
                                   LoadBookByIdUseCase loadBookByIdUseCase, BookResourcePort bookResourcePort, UiBackgroundExecutor executor, ApplicationState applicationState,
                                   DialogService dialogService, ApplicationSettingsPort settings, ApplicationEventPublisher eventPublisher,
                                   OperationCenterService operationCenter) {
        this.downloadBookUseCase = downloadBookUseCase;
        this.removeLocalBookCopyUseCase = removeLocalBookCopyUseCase;
        this.loadBookByIdUseCase = loadBookByIdUseCase;
        this.bookResourcePort = bookResourcePort;
        this.executor = executor;
        this.applicationState = applicationState;
        this.dialogService = dialogService;
        this.eventPublisher = eventPublisher;
        this.operationCenter = operationCenter;
        int permits = Math.max(1, Math.min(16, settings.getInt("online.maxParallelDownloads", 2)));
        this.downloadSlots = new Semaphore(permits, true);
    }

    public CompletableFuture<Path> ensureLocal(BookDto book) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        return loadAuthoritative(book).thenCompose(authoritative ->
                download(authoritative, false).whenComplete((path, error) -> {
                    if (error == null) copyStorageState(authoritative, book);
                }));
    }

    /** Load the authoritative DB row first. List workspaces intentionally carry only a lightweight DTO. */
    public CompletableFuture<Path> ensureLocal(BookId bookId) {
        if (bookId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("BookId is null"));
        return executor.submitCollectionScoped(LibraryOperationType.QUERY,
                        () -> loadBookByIdUseCase.execute(bookId)
                        .orElseThrow(() -> new IllegalStateException(UiText.text("ui.java.book_download_coordinator.7e6db1b645") + bookId)))
                .thenCompose(book -> download(book, false));
    }

    /**
     * Ensures physical availability and reports whether I/O was actually required.
     * Batch UI uses this to distinguish newly downloaded books from already-local ones.
     */
    public CompletableFuture<EnsureLocalOutcome> ensureLocalWithStatus(BookId bookId) {
        return ensureLocalWithStatus(bookId, true);
    }

    private CompletableFuture<EnsureLocalOutcome> ensureLocalWithStatus(BookId bookId, boolean showErrors) {
        if (bookId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("BookId is null"));
        return executor.submitCollectionScoped(LibraryOperationType.QUERY,
                        () -> loadBookByIdUseCase.execute(bookId)
                        .orElseThrow(() -> new IllegalStateException(UiText.text("ui.java.book_download_coordinator.7e6db1b645") + bookId)))
                .thenCompose(book -> {
                    normalizeLegacyRemoteStorage(book);
                    var existing = bookResourcePort.locateBookFile(
                            book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry());
                    if (existing.isPresent()) {
                        return CompletableFuture.completedFuture(new EnsureLocalOutcome(existing.get(), true));
                    }
                    return download(book, false, showErrors, showErrors).thenApply(path -> new EnsureLocalOutcome(path, false));
                });
    }

    public record EnsureLocalOutcome(Path path, boolean alreadyLocal) { }

    public record BatchDownloadResult(int requested, int downloaded, int alreadyLocal, int failed, List<String> errors) {
        public boolean successful() { return failed == 0; }
    }

    /**
     * Downloads the selected books while keeping the current workspace/navigation intact.
     * A compact progress window shows connection, completed count and committed library saves.
     */
    public CompletableFuture<BatchDownloadResult> downloadBatch(List<BookId> bookIds, Window owner) {
        List<BookId> ids = bookIds == null ? List.of() : bookIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return CompletableFuture.completedFuture(new BatchDownloadResult(0, 0, 0, 0, List.of()));
        if (!Platform.isFxApplicationThread()) {
            CompletableFuture<BatchDownloadResult> bridge = new CompletableFuture<>();
            Platform.runLater(() -> downloadBatch(ids, owner).whenComplete((result, error) -> {
                if (error == null) bridge.complete(result); else bridge.completeExceptionally(error);
            }));
            return bridge;
        }

        BookDownloadProgressDialog progressDialog = new BookDownloadProgressDialog(owner, ids.size());
        progressDialog.show();
        progressDialog.update(0, ids.size(), 0, 0, 0, UiText.text("ui.java.book_download_coordinator.0f720c36df"));

        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger downloaded = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger alreadyLocal = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        CompletableFuture<?>[] tasks = ids.stream().map(id -> ensureLocalWithStatus(id, false).handle((outcome, error) -> {
            if (error != null) {
                failed.incrementAndGet();
                errors.add(id + ": " + compactError(unwrap(error)));
            } else if (outcome.alreadyLocal()) {
                alreadyLocal.incrementAndGet();
            } else {
                downloaded.incrementAndGet();
            }
            int done = completed.incrementAndGet();
            Platform.runLater(() -> progressDialog.update(done, ids.size(), downloaded.get(), alreadyLocal.get(), failed.get(),
                    UiText.text("ui.java.book_download_coordinator.ae22229ad2")));
            return null;
        })).toArray(CompletableFuture[]::new);

        return CompletableFuture.allOf(tasks).thenApply(ignored -> {
            BatchDownloadResult result = new BatchDownloadResult(ids.size(), downloaded.get(), alreadyLocal.get(), failed.get(), List.copyOf(errors));
            Platform.runLater(() -> {
                progressDialog.complete(ids.size(), result.downloaded(), result.alreadyLocal(), result.failed());
                if (result.downloaded() > 0) eventPublisher.publishEvent(new NavigationRefreshEvent());
            });
            return result;
        });
    }

    /**
     * Export preflight that stays silent when every selected book is already physically local.
     * Only missing books are handed to the normal batch downloader, so an export of local books
     * does not flash an unnecessary download window.
     */
    public CompletableFuture<BatchDownloadResult> prepareForExport(List<BookId> bookIds, Window owner) {
        List<BookId> ids = bookIds == null ? List.of() : bookIds.stream()
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return CompletableFuture.completedFuture(new BatchDownloadResult(0, 0, 0, 0, List.of()));

        return executor.submitCollectionScoped(LibraryOperationType.DOWNLOAD, () -> {
            java.util.ArrayList<BookId> missing = new java.util.ArrayList<>();
            int local = 0;
            for (BookId id : ids) {
                BookDto book = loadBookByIdUseCase.execute(id).orElse(null);
                if (book == null) {
                    missing.add(id);
                    continue;
                }
                normalizeLegacyRemoteStorage(book);
                boolean present = bookResourcePort.locateBookFile(
                        book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry()).isPresent();
                if (present) local++; else missing.add(id);
            }
            return new ExportPreflight(List.copyOf(missing), local);
        }).thenCompose(preflight -> {
            if (preflight.missing().isEmpty()) {
                return CompletableFuture.completedFuture(new BatchDownloadResult(
                        ids.size(), 0, preflight.alreadyLocal(), 0, List.of()));
            }
            return downloadBatch(preflight.missing(), owner).thenApply(result -> new BatchDownloadResult(
                    ids.size(),
                    result.downloaded(),
                    preflight.alreadyLocal() + result.alreadyLocal(),
                    result.failed(),
                    result.errors()));
        });
    }

    private record ExportPreflight(List<BookId> missing, int alreadyLocal) { }

    /**
     * Open/read guard using the authoritative DB row before checking the physical file.
     * Lightweight table DTOs may intentionally omit online/storage metadata.
     */
    public CompletableFuture<Path> ensureLocalForOpen(BookDto book) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        return loadAuthoritative(book).thenCompose(authoritative ->
                ensureLocalForOpenAuthoritative(authoritative).whenComplete((path, error) -> {
                    if (error == null) copyStorageState(authoritative, book);
                }));
    }

    public CompletableFuture<Path> ensureLocalForOpen(BookId bookId) {
        if (bookId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("BookId is null"));
        return executor.submitCollectionScoped(LibraryOperationType.QUERY,
                        () -> loadBookByIdUseCase.execute(bookId)
                        .orElseThrow(() -> new IllegalStateException(UiText.text("ui.java.book_download_coordinator.7e6db1b645") + bookId)))
                .thenCompose(this::ensureLocalForOpenAuthoritative);
    }

    private CompletableFuture<Path> ensureLocalForOpenAuthoritative(BookDto book) {
        normalizeLegacyRemoteStorage(book);
        var existing = bookResourcePort.locateBookFile(
                book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry());
        if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get());

        if (!Platform.isFxApplicationThread()) {
            CompletableFuture<Path> result = new CompletableFuture<>();
            Platform.runLater(() -> ensureLocalForOpenAuthoritative(book).whenComplete((path, error) -> {
                if (error == null) result.complete(path);
                else result.completeExceptionally(error);
            }));
            return result;
        }

        boolean approved = dialogService.showConfirmation(
                UiText.text("ui.java.book_download_coordinator.ab4bcb3327"),
                book.getTitle() == null || book.getTitle().isBlank() ? UiText.text("ui.java.book_download_coordinator.56f377fe01") : book.getTitle(),
                UiText.text("ui.java.book_download_coordinator.0e3394ae8f"));
        if (!approved) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                    UiText.text("ui.java.book_download_coordinator.39fb6cb400")));
        }
        return download(book, false);
    }

    public CompletableFuture<Path> downloadUpdate(BookDto book) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        return loadAuthoritative(book).thenCompose(authoritative ->
                download(authoritative, true).whenComplete((path, error) -> {
                    if (error == null) copyStorageState(authoritative, book);
                }));
    }

    private CompletableFuture<BookDto> loadAuthoritative(BookDto fallback) {
        if (fallback == null || fallback.getId() == null || fallback.getId().isBlank()) {
            return CompletableFuture.completedFuture(fallback);
        }
        BookId id;
        try {
            id = BookId.fromString(fallback.getId());
        } catch (RuntimeException invalidId) {
            return CompletableFuture.completedFuture(fallback);
        }
        return executor.submitCollectionScoped(LibraryOperationType.QUERY,
                () -> loadBookByIdUseCase.execute(id).orElse(fallback));
    }

    private CompletableFuture<Path> download(BookDto book, boolean force) {
        return download(book, force, true, true);
    }

    private CompletableFuture<Path> download(BookDto book, boolean force, boolean showErrors) {
        return download(book, force, showErrors, true);
    }

    private CompletableFuture<Path> download(BookDto book, boolean force, boolean showErrors, boolean publishNavigationRefresh) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        normalizeLegacyRemoteStorage(book);

        if (!force) {
            var existing = bookResourcePort.locateBookFile(book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry());
            if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get());
        }

        Collection collection = applicationState.getCurrentLibraryCollection();
        if (collection == null) {
            return failedVisible(UiText.text("ui.java.book_download_coordinator.876d0b4d3f"));
        }
        if ((collection.getUrl() == null || collection.getUrl().isBlank())
                && (collection.getConnectionScript() == null || collection.getConnectionScript().isBlank())) {
            return failedVisible(UiText.text("ui.java.book_download_coordinator.bd6ccfa947"));
        }

        AtomicBoolean cancel = new AtomicBoolean(false);
        AtomicBoolean previous = active.putIfAbsent(book.getId(), cancel);
        if (previous != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(UiText.text("ui.java.book_download_coordinator.56699e79ab")));
        }

        String collectionId = collection.getId() == null ? "" : collection.getId();
        String operationTitle = UiText.text("ui.java.book_download_coordinator.f6cd0d8490") + safeBookTitle(book);
        String operationId = operationCenter.start(operationTitle, collectionId, OperationKind.BOOK_DOWNLOAD,
                OperationStage.BOOK_DOWNLOAD, true);

        // Показуємо початок завантаження
        Platform.runLater(() -> {
            applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.28e9bf28d4") + book.getTitle());
            applicationState.getStatusBar().setProgressVisible(true);
            applicationState.getStatusBar().setProgress(0);
        });

        long startTime = System.currentTimeMillis();

        return executor.submitCollectionScoped(LibraryOperationType.DOWNLOAD, () -> {
                    boolean acquired = false;
                    try {
                        while (!cancel.get() && !Thread.currentThread().isInterrupted()) {
                            if (downloadSlots.tryAcquire(100, TimeUnit.MILLISECONDS)) {
                                acquired = true;
                                break;
                            }
                        }
                        if (!acquired || cancel.get() || Thread.currentThread().isInterrupted()) {
                            throw new java.util.concurrent.CancellationException(UiText.text("ui.java.book_download_coordinator.b23355d41c"));
                        }

                        // Оновлюємо статус
                        Platform.runLater(() -> {
                            applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.ceea793bc6") + book.getTitle() + UiText.text("ui.java.book_download_coordinator.0cafc3a4a6"));
                        });

                        BookDto effectiveBook = loadBookByIdUseCase.execute(BookId.fromString(book.getId())).orElse(book);
                        normalizeLegacyRemoteStorage(effectiveBook);
                        return downloadBookUseCase.execute(effectiveBook, collection, cancel, value -> {
                            long processed = Math.max(0L, Math.min(1000L, Math.round(value * 1000.0)));
                            operationCenter.accept(operationTitle, collectionId, OperationKind.BOOK_DOWNLOAD,
                                    OperationProgress.stage(operationId, OperationStage.BOOK_DOWNLOAD, true)
                                            .withProgress(processed, 1000)
                                            .withCurrentItem(safeBookTitle(effectiveBook)));
                            Platform.runLater(() -> {
                                applicationState.getStatusBar().setProgress(value);
                                int percent = (int) Math.round(value * 100);
                                applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.28e9bf28d4") + effectiveBook.getTitle() + " (" + percent + "%)");
                            });
                        }, force);
                    } finally {
                        if (acquired) downloadSlots.release();
                    }
                })
                .whenComplete((path, error) -> {
                    active.remove(book.getId(), cancel);
                    long duration = System.currentTimeMillis() - startTime;
                    BookDto refreshed = error == null ? reloadSafely(book) : book;
                    // Complete the future only after the caller's DTO has authoritative storage metadata.
                    // External-open callbacks may run immediately when this stage completes and must not
                    // observe the pre-download remote folder/fileName.
                    if (error == null) {
                        copyStorageState(refreshed, book);
                        operationCenter.complete(operationId, UiText.text("ui.java.book_download_coordinator.a0c024460b") + Math.max(0, duration / 1000) + UiText.text("ui.java.book_download_coordinator.488d98cad9"));
                    } else {
                        Throwable operationFailure = unwrap(error);
                        if (UiExceptionSupport.isCancellation(operationFailure)) {
                            operationCenter.cancel(operationId, UiText.text("ui.java.book_download_coordinator.b23355d41c"));
                        } else {
                            operationCenter.fail(operationId, operationFailure);
                        }
                    }
                    Platform.runLater(() -> {
                        applicationState.getStatusBar().setProgressVisible(false);
                        if (error == null) {
                            applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.a10fff9611") + refreshed.getTitle() + " (" + duration / 1000 + UiText.text("ui.java.book_download_coordinator.3023b67d72"));
                            BookDto details = applicationState.getBookDetails().getCurrentBook();
                            if (details != null && book.getId().equals(details.getId())) {
                                applicationState.getBookDetails().setCurrentBook(refreshed);
                            }
                            applicationState.getBookTable().getBooks().stream()
                                    .filter(row -> book.getId().equals(row.getId()))
                                    .findFirst()
                                    .ifPresent(row -> {
                                        row.setLocal(refreshed.isLocal());
                                        row.setCollectionRoot(refreshed.getCollectionRoot());
                                        row.setFolder(refreshed.getFolder());
                                        row.setFileName(refreshed.getFileName());
                                        row.setArchiveEntry(refreshed.getArchiveEntry());
                                        row.setFileSize(refreshed.getFileSize());
                                    });
                            if (publishNavigationRefresh) eventPublisher.publishEvent(new NavigationRefreshEvent());
                        } else {
                            Throwable cause = unwrap(error);
                            applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.4f6a3c9ef7") + book.getTitle());
                            if (!UiExceptionSupport.isCancellation(cause)) {
                                log.error("Завантаження книги {} завершилося помилкою: {}", book.getId(), compactError(cause));
                                if (showErrors) dialogService.showError(UiText.text("ui.import.stage.downloading"), userVisibleDownloadError(cause));
                            }
                        }
                    });
                });
    }

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
                log.debug("Нормалізовано онлайн-архів для книги {}: {} -> {}",
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
        if (text == null || text.isBlank()) return "";
        int comma = text.indexOf(',');
        return (comma > 0 ? text.substring(0, comma) : text).trim();
    }

    private static boolean isTransientCatalogRoot(String root) {
        if (root == null || root.isBlank()) return false;
        String normalized = root.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("/.myhomelibcorp/cache/catalog-updates")
                || normalized.endsWith("/.myhomelibcorp/cache/catalog-updates");
    }

    private BookDto reloadSafely(BookDto fallback) {
        if (fallback == null || fallback.getId() == null || fallback.getId().isBlank()) return fallback;
        try {
            return loadBookByIdUseCase.execute(BookId.fromString(fallback.getId())).orElse(fallback);
        } catch (RuntimeException error) {
            log.warn("Книгу {} завантажено, але не вдалося перечитати оновлені metadata", fallback.getId(), error);
            return fallback;
        }
    }

    private static void copyStorageState(BookDto source, BookDto target) {
        if (source == null || target == null) return;
        target.setCollectionRoot(source.getCollectionRoot());
        target.setFolder(source.getFolder());
        target.setFileName(source.getFileName());
        target.setArchiveEntry(source.getArchiveEntry());
        target.setFileSize(source.getFileSize());
        target.setLocal(source.isLocal());
        target.setMissingSince(source.getMissingSince());
        target.setLibId(source.getLibId());
        target.setSourceUrl(source.getSourceUrl());
    }

    private static String safeBookTitle(BookDto book) {
        if (book == null || book.getTitle() == null || book.getTitle().isBlank()) return UiText.text("ui.java.book_download_coordinator.6424359615");
        return book.getTitle().trim();
    }

    private CompletableFuture<Path> failedVisible(String message) {
        Platform.runLater(() -> dialogService.showError(UiText.text("ui.import.stage.downloading"), message));
        return CompletableFuture.failedFuture(new IllegalStateException(message));
    }

    public CompletableFuture<Integer> removeLocalCopy(BookDto book) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        if (isDownloading(book)) return CompletableFuture.failedFuture(new IllegalStateException(UiText.text("ui.java.book_download_coordinator.b536619b61")));
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Активну колекцію не вибрано"));
        }
        return loadAuthoritative(book).thenCompose(authoritative ->
                executor.submitCollectionScoped(LibraryOperationType.QUERY,
                                () -> removeLocalBookCopyUseCase.preview(authoritative))
                        .thenCompose(preview -> confirmSingleRemoval(authoritative, preview)
                                .thenCompose(approved -> approved
                                        ? executor.submit(() -> removeLocalBookCopyUseCase.execute(authoritative, expectedCollectionId))
                                        : CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(UiText.text("ui.java.book_download_coordinator.fd5c8520d7"))))))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (error == null) {
                        book.setLocal(false);
                        book.setMissingSince(null);
                        applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.2b14cd684a") + book.getTitle());
                        eventPublisher.publishEvent(new NavigationRefreshEvent());
                    } else {
                        Throwable cause = unwrap(error);
                        if (!(cause instanceof java.util.concurrent.CancellationException)) {
                            dialogService.showError(UiText.text("ui.java.book_download_coordinator.b4d025c63a"), UiExceptionSupport.message(cause));
                        }
                    }
                }));
    }

    public CompletableFuture<Integer> removeLocalCopies(List<BookId> bookIds) {
        if (bookIds == null || bookIds.isEmpty()) return CompletableFuture.completedFuture(0);
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Активну колекцію не вибрано"));
        }
        return executor.submitCollectionScoped(LibraryOperationType.QUERY, () -> {
            LinkedHashMap<String, PlannedRemoval> unique = new LinkedHashMap<>();
            for (BookId id : bookIds.stream().distinct().toList()) {
                BookDto book = loadBookByIdUseCase.execute(id).orElse(null);
                if (book == null) continue;
                var preview = removeLocalBookCopyUseCase.preview(book);
                String key = preview.sharedArchive() && preview.physicalPath() != null
                        ? "archive:" + preview.physicalPath().toAbsolutePath().normalize()
                        : "book:" + book.getId();
                unique.putIfAbsent(key, new PlannedRemoval(book, preview));
            }
            return List.copyOf(unique.values());
        }).thenCompose(plans -> confirmBatchRemoval(plans).thenCompose(approved -> {
            if (!approved) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(UiText.text("ui.java.book_download_coordinator.fd5c8520d7")));
            return executor.submit(() -> removeLocalBookCopyUseCase.executeBatch(
                    plans.stream().map(PlannedRemoval::book).toList(), expectedCollectionId));
        })).whenComplete((count, error) -> Platform.runLater(() -> {
            if (error == null) {
                applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.04a43f7a95") + count);
                eventPublisher.publishEvent(new NavigationRefreshEvent());
            } else {
                Throwable cause = unwrap(error);
                if (!(cause instanceof java.util.concurrent.CancellationException)) {
                    dialogService.showError(UiText.text("ui.java.book_download_coordinator.79701faed9"), UiExceptionSupport.message(cause));
                }
            }
        }));
    }

    private CompletableFuture<Boolean> confirmSingleRemoval(BookDto book, RemoveLocalBookCopyUseCase.RemovalPreview preview) {
        String details = preview.sharedArchive()
                ? UiText.text("ui.java.book_download_coordinator.2e028d2f14") + displayPath(preview.physicalPath()) + UiText.text("ui.java.book_download_coordinator.6ad8ec352c") + preview.affectedBooks() +
                  UiText.text("ui.java.book_download_coordinator.c68c3e7f8e")
                : UiText.text("ui.java.book_download_coordinator.813eeb90bc") + displayPath(preview.physicalPath()) + UiText.text("ui.java.book_download_coordinator.d22c2a822e");
        return confirmOnFx(UiText.text("ui.java.book_download_coordinator.aa69d6a084"), book.getTitle(), details);
    }

    private CompletableFuture<Boolean> confirmBatchRemoval(List<PlannedRemoval> plans) {
        int physicalFiles = (int) plans.stream().filter(p -> p.preview().physicalPath() != null).count();
        int affectedBooks = plans.stream().mapToInt(p -> p.preview().affectedBooks()).sum();
        long sharedArchives = plans.stream().filter(p -> p.preview().sharedArchive()).count();
        String details = UiText.text("ui.java.book_download_coordinator.e9624deb1e") + physicalFiles + UiText.text("ui.java.book_download_coordinator.84f73be152") + affectedBooks
                + (sharedArchives > 0 ? UiText.text("ui.java.book_download_coordinator.4356af2404") + sharedArchives : "")
                + UiText.text("ui.java.book_download_coordinator.a17432d8d1");
        return confirmOnFx(UiText.text("ui.java.book_download_coordinator.5efe8f80f6"), UiText.text("ui.java.book_download_coordinator.02db26baaf") + plans.size(), details);
    }

    private CompletableFuture<Boolean> confirmOnFx(String title, String header, String details) {
        if (Platform.isFxApplicationThread()) {
            return CompletableFuture.completedFuture(dialogService.showConfirmation(title, header, details));
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { result.complete(dialogService.showConfirmation(title, header, details)); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        return result;
    }

    private static String displayPath(Path path) {
        return path == null ? UiText.text("ui.java.book_download_coordinator.73e081d220") : path.toString();
    }

    private record PlannedRemoval(BookDto book, RemoveLocalBookCopyUseCase.RemovalPreview preview) { }

    public boolean cancel(BookDto book) {
        if (book == null) return false;
        AtomicBoolean flag = active.get(book.getId());
        if (flag == null) return false;
        flag.set(true);
        applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.a0a3d60bc9"));
        return true;
    }

    public boolean isDownloading(BookDto book) {
        return book != null && active.containsKey(book.getId());
    }

    private static String userVisibleDownloadError(Throwable error) {
        String detail = compactError(error);
        String normalized = detail.toLowerCase(java.util.Locale.ROOT);
        if (normalized.contains("sqlite_busy") || normalized.contains("database is locked")
                || normalized.contains("database table is locked")) {
            return UiText.text("ui.java.book_download_coordinator.f3e9f924e2")
                    + UiText.text("ui.java.book_download_coordinator.625e661c57");
        }
        if (normalized.contains("preparedstatementcallback") || normalized.contains("uncategorized sqlexception")) {
            return UiText.text("ui.java.book_download_coordinator.2b2525927d")
                    + UiText.text("ui.java.book_download_coordinator.44b16f26d7");
        }
        return UiExceptionSupport.message(error);
    }

    private static String compactError(Throwable error) {
        return error == null ? "" : UiExceptionSupport.technicalMessage(error);
    }

    private Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException || current instanceof java.util.concurrent.ExecutionException)) current = current.getCause();
        return current;
    }
}