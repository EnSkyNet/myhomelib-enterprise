package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.util.UiExecutor;
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
    private final int maxParallelDownloads;
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
        this.maxParallelDownloads = permits;
        this.downloadSlots = new Semaphore(permits, true);
    }

    /**
     * Reconciles a stale catalogue DTO with a physical local copy without starting network I/O.
     * This is intentionally cheap enough for Book Details selection and prevents a stale remote
     * flag from re-downloading a file that is already present in the active collection root.
     */
    public boolean reconcileLocalClaim(BookDto book) {
        if (book == null) return false;
        Collection collection = applicationState.getCurrentLibraryCollection();
        if (collection == null) return book.isLocal();
        normalizeLegacyRemoteStorage(book, collection);
        return locateExistingLocal(book, collection).isPresent();
    }

    /** Returns whether the active collection has a remote source that can materialize this book. */
    public boolean canDownload(BookDto book) {
        if (book == null) return false;
        if (book.isLocal() || reconcileLocalClaim(book)) return false;
        Collection collection = applicationState.getCurrentLibraryCollection();
        return collection != null && isOnlineCollection(collection);
    }

    public CompletableFuture<Path> ensureLocal(BookDto book) {
        return ensureLocal(book, applicationState.getCurrentLibraryCollectionId());
    }

    /** Collection-stable variant for multi-stage UI flows that already captured the DTO in a prior async step. */
    public CompletableFuture<Path> ensureLocal(BookDto book, String expectedCollectionId) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(UiText.text("ui.error.collection_not_selected")));
        }
        return loadAuthoritative(book, expectedCollectionId, LibraryOperationType.DOWNLOAD).thenCompose(authoritative ->
                download(authoritative, false, true, true, expectedCollectionId).whenComplete((path, error) -> {
                    if (error == null) copyStorageState(authoritative, book);
                }));
    }

    /** Load the authoritative DB row first. List workspaces intentionally carry only a lightweight DTO. */
    public CompletableFuture<Path> ensureLocal(BookId bookId) {
        if (bookId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("BookId is null"));
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        return executor.submitCollectionScopedNetwork(LibraryOperationType.DOWNLOAD,
                        () -> {
                            requireCurrentCollection(expectedCollectionId, "ui.error.collection_changed.book_download");
                            return loadBookByIdUseCase.execute(bookId)
                                    .orElseThrow(() -> new IllegalStateException(UiText.text("ui.java.book_download_coordinator.7e6db1b645") + bookId));
                        })
                .thenCompose(book -> download(book, false, true, true, expectedCollectionId));
    }

    /**
     * Ensures physical availability and reports whether I/O was actually required.
     * Batch UI uses this to distinguish newly downloaded books from already-local ones.
     */
    public CompletableFuture<EnsureLocalOutcome> ensureLocalWithStatus(BookId bookId) {
        return ensureLocalWithStatus(bookId, true);
    }

    private CompletableFuture<EnsureLocalOutcome> ensureLocalWithStatus(BookId bookId, boolean showErrors) {
        return ensureLocalWithStatus(bookId, showErrors, applicationState.getCurrentLibraryCollectionId());
    }

    private CompletableFuture<EnsureLocalOutcome> ensureLocalWithStatus(
            BookId bookId, boolean showErrors, String expectedCollectionId) {
        if (bookId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("BookId is null"));
        // The complete "ensure local" workflow is download-scoped. Using QUERY here and
        // DOWNLOAD in the continuation creates a QUERY -> DOWNLOAD lifecycle transition. Under
        // a large batch, many pre-acquired QUERY leases coexist and almost every continuation is
        // rejected while sibling QUERY leases are still queued.
        return executor.submitCollectionScopedNetwork(LibraryOperationType.DOWNLOAD,
                        () -> {
                            requireCurrentCollection(expectedCollectionId, "ui.error.collection_changed.book_download");
                            BookDto authoritative = loadBookByIdUseCase.execute(bookId)
                                    .orElseThrow(() -> new IllegalStateException(UiText.text("ui.java.book_download_coordinator.7e6db1b645") + bookId));
                            Collection collection = applicationState.getCurrentLibraryCollection();
                            normalizeLegacyRemoteStorage(authoritative, collection);
                            return authoritative;
                        })
                .thenCompose(book -> {
                    Collection collection = applicationState.getCurrentLibraryCollection();
                    var existing = locateExistingLocal(book, collection);
                    if (existing.isPresent()) {
                        return CompletableFuture.completedFuture(new EnsureLocalOutcome(existing.get(), true));
                    }
                    return download(book, false, showErrors, showErrors, expectedCollectionId)
                            .thenApply(path -> new EnsureLocalOutcome(path, false));
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
        return downloadBatch(bookIds, owner, applicationState.getCurrentLibraryCollectionId());
    }

    private CompletableFuture<BatchDownloadResult> downloadBatch(
            List<BookId> bookIds, Window owner, String expectedCollectionId) {
        List<BookId> ids = bookIds == null ? List.of() : bookIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return CompletableFuture.completedFuture(new BatchDownloadResult(0, 0, 0, 0, List.of()));
        if (!Platform.isFxApplicationThread()) {
            return UiExecutor.supplyOnUiThread(() -> downloadBatch(ids, owner, expectedCollectionId))
                    .thenCompose(java.util.function.Function.identity());
        }
        if (!isCurrentCollection(expectedCollectionId)) {
            return CompletableFuture.failedFuture(new IllegalStateException(UiText.text("ui.error.collection_changed.book_download")));
        }

        BookDownloadProgressDialog progressDialog = new BookDownloadProgressDialog(owner, ids.size());
        progressDialog.show();
        progressDialog.update(0, ids.size(), 0, 0, 0, UiText.text("ui.java.book_download_coordinator.0f720c36df"));

        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger downloaded = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger alreadyLocal = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        // Do not enqueue the whole selection at once. UiBackgroundExecutor intentionally has a
        // bounded queue, and collection-scoped tasks acquire their lifecycle lease before enqueue.
        // Fan-out of hundreds of books can therefore overflow the queue and, before the download
        // scope fix above, created QUERY -> DOWNLOAD conflicts. Keep only the configured number of
        // download workflows in flight; each lane schedules its next book only after the previous one
        // has reached a terminal state.
        int lanesCount = Math.max(1, Math.min(maxParallelDownloads, ids.size()));
        CompletableFuture<?>[] lanes = new CompletableFuture<?>[lanesCount];
        for (int laneIndex = 0; laneIndex < lanesCount; laneIndex++) {
            CompletableFuture<Void> lane = CompletableFuture.completedFuture(null);
            for (int index = laneIndex; index < ids.size(); index += lanesCount) {
                BookId id = ids.get(index);
                lane = lane.thenCompose(ignored -> ensureLocalWithStatus(id, false, expectedCollectionId)
                        .handle((outcome, error) -> {
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
                        }));
            }
            lanes[laneIndex] = lane;
        }

        return CompletableFuture.allOf(lanes).thenApply(ignored -> {
            BatchDownloadResult result = new BatchDownloadResult(ids.size(), downloaded.get(), alreadyLocal.get(), failed.get(), List.copyOf(errors));
            Platform.runLater(() -> {
                progressDialog.complete(ids.size(), result.downloaded(), result.alreadyLocal(), result.failed());
                if (result.downloaded() > 0 && isCurrentCollection(expectedCollectionId)) {
                    eventPublisher.publishEvent(new NavigationRefreshEvent());
                }
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
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();

        return executor.submitCollectionScoped(LibraryOperationType.DOWNLOAD, () -> {
            if (!isCurrentCollection(expectedCollectionId)) {
                throw new IllegalStateException(UiText.text("ui.error.collection_changed.export_prepare"));
            }
            java.util.ArrayList<BookId> missing = new java.util.ArrayList<>();
            int local = 0;
            for (BookId id : ids) {
                BookDto book = loadBookByIdUseCase.execute(id).orElse(null);
                if (book == null) {
                    missing.add(id);
                    continue;
                }
                Collection collection = applicationState.getCurrentLibraryCollection();
                normalizeLegacyRemoteStorage(book, collection);
                boolean present = locateExistingLocal(book, collection).isPresent();
                if (present) local++; else missing.add(id);
            }
            return new ExportPreflight(List.copyOf(missing), local);
        }).thenCompose(preflight -> {
            if (preflight.missing().isEmpty()) {
                return CompletableFuture.completedFuture(new BatchDownloadResult(
                        ids.size(), 0, preflight.alreadyLocal(), 0, List.of()));
            }
            return downloadBatch(preflight.missing(), owner, expectedCollectionId).thenApply(result -> new BatchDownloadResult(
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
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        return loadAuthoritative(book, expectedCollectionId, LibraryOperationType.DOWNLOAD).thenCompose(authoritative ->
                ensureLocalForOpenAuthoritative(authoritative, expectedCollectionId).whenComplete((path, error) -> {
                    if (error == null) copyStorageState(authoritative, book);
                }));
    }

    public CompletableFuture<Path> ensureLocalForOpen(BookId bookId) {
        if (bookId == null) return CompletableFuture.failedFuture(new IllegalArgumentException("BookId is null"));
        String expectedCollectionId = applicationState.getCurrentLibraryCollectionId();
        return executor.submitCollectionScopedNetwork(LibraryOperationType.DOWNLOAD,
                        () -> {
                            requireCurrentCollection(expectedCollectionId, "ui.error.collection_changed.book_open");
                            BookDto authoritative = loadBookByIdUseCase.execute(bookId)
                                    .orElseThrow(() -> new IllegalStateException(UiText.text("ui.java.book_download_coordinator.7e6db1b645") + bookId));
                            Collection collection = applicationState.getCurrentLibraryCollection();
                            normalizeLegacyRemoteStorage(authoritative, collection);
                            return authoritative;
                        })
                .thenCompose(book -> ensureLocalForOpenAuthoritative(book, expectedCollectionId));
    }

    private CompletableFuture<Path> ensureLocalForOpenAuthoritative(BookDto book, String expectedCollectionId) {
        if (!isCurrentCollection(expectedCollectionId)) {
            return CompletableFuture.failedFuture(new IllegalStateException(UiText.text("ui.error.collection_changed.book_open")));
        }
        Collection collection = applicationState.getCurrentLibraryCollection();
        if (collection == null || !expectedCollectionId.equals(collection.getId())) {
            return CompletableFuture.failedFuture(new IllegalStateException(UiText.text("ui.error.collection_changed.book_open")));
        }
        normalizeLegacyRemoteStorage(book, collection);
        var existing = locateExistingLocal(book, collection);
        if (existing.isPresent()) return CompletableFuture.completedFuture(existing.get());

        if (!Platform.isFxApplicationThread()) {
            return UiExecutor.supplyOnUiThread(() -> ensureLocalForOpenAuthoritative(book, expectedCollectionId))
                    .thenCompose(java.util.function.Function.identity());
        }

        boolean approved = dialogService.showConfirmation(
                UiText.text("ui.java.book_download_coordinator.ab4bcb3327"),
                book.getTitle() == null || book.getTitle().isBlank() ? UiText.text("ui.java.book_download_coordinator.56f377fe01") : book.getTitle(),
                UiText.text("ui.java.book_download_coordinator.0e3394ae8f"));
        if (!approved) {
            return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(
                    UiText.text("ui.java.book_download_coordinator.39fb6cb400")));
        }
        return download(book, false, true, true, expectedCollectionId);
    }

    public CompletableFuture<Path> downloadUpdate(BookDto book) {
        return downloadUpdate(book, applicationState.getCurrentLibraryCollectionId());
    }

    /** Collection-stable variant for update notifications loaded asynchronously. */
    public CompletableFuture<Path> downloadUpdate(BookDto book, String expectedCollectionId) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(UiText.text("ui.error.collection_not_selected")));
        }
        return loadAuthoritative(book, expectedCollectionId, LibraryOperationType.DOWNLOAD).thenCompose(authoritative ->
                download(authoritative, true, true, true, expectedCollectionId).whenComplete((path, error) -> {
                    if (error == null) copyStorageState(authoritative, book);
                }));
    }

    private CompletableFuture<BookDto> loadAuthoritative(BookDto fallback, String expectedCollectionId) {
        return loadAuthoritative(fallback, expectedCollectionId, LibraryOperationType.QUERY);
    }

    private CompletableFuture<BookDto> loadAuthoritative(
            BookDto fallback, String expectedCollectionId, LibraryOperationType operationType) {
        if (fallback == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        }
        if (fallback.getId() == null || fallback.getId().isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Book has no stable id"));
        }
        BookId id;
        try {
            id = BookId.fromString(fallback.getId());
        } catch (RuntimeException invalidId) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Book has an invalid stable id", invalidId));
        }
        LibraryOperationType scope = operationType == null ? LibraryOperationType.QUERY : operationType;
        java.util.concurrent.Callable<BookDto> load = () -> {
            requireCurrentCollection(expectedCollectionId, "ui.error.collection_changed.book_data_load");
            return loadBookByIdUseCase.execute(id)
                    .orElseThrow(() -> new IllegalStateException(
                            UiText.format("ui.error.book_not_found_expected_collection", id)));
        };
        // Download preflight must not sit behind passive UI/query backlog while the user waits.
        // It performs only a short DB lookup but belongs to the same latency path as the network request.
        return scope == LibraryOperationType.DOWNLOAD
                ? executor.submitCollectionScopedNetwork(scope, load)
                : executor.submitCollectionScoped(scope, load);
    }

    private CompletableFuture<Path> download(BookDto book, boolean force) {
        return download(book, force, true, true, applicationState.getCurrentLibraryCollectionId());
    }

    private CompletableFuture<Path> download(BookDto book, boolean force, boolean showErrors) {
        return download(book, force, showErrors, true, applicationState.getCurrentLibraryCollectionId());
    }

    private CompletableFuture<Path> download(BookDto book, boolean force, boolean showErrors, boolean publishNavigationRefresh) {
        return download(book, force, showErrors, publishNavigationRefresh, applicationState.getCurrentLibraryCollectionId());
    }

    private CompletableFuture<Path> download(BookDto book, boolean force, boolean showErrors,
                                             boolean publishNavigationRefresh, String expectedCollectionId) {
        if (book == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Book is null"));
        Collection collection = applicationState.getCurrentLibraryCollection();
        if (collection == null) {
            return failedVisible(UiText.text("ui.java.book_download_coordinator.876d0b4d3f"));
        }
        if (expectedCollectionId == null || expectedCollectionId.isBlank()
                || collection.getId() == null || !expectedCollectionId.equals(collection.getId())) {
            return CompletableFuture.failedFuture(new IllegalStateException(UiText.text("ui.error.collection_changed.book_download")));
        }

        // Never normalize or resolve a DTO from collection A using collection B settings.
        normalizeLegacyRemoteStorage(book, collection);
        if (!force) {
            var existing = locateExistingLocal(book, collection);
            if (existing.isPresent()) {
                log.info("ONLINE_DOWNLOAD_SKIP_LOCAL title='{}' id={} path='{}'",
                        safeBookTitle(book), book.getId(), existing.get());
                return CompletableFuture.completedFuture(existing.get());
            }
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
            if (!isCurrentCollection(collectionId)) return;
            applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.28e9bf28d4") + book.getTitle());
            applicationState.getStatusBar().setProgressVisible(true);
            applicationState.getStatusBar().setProgress(0);
        });

        long startTime = System.currentTimeMillis();
        log.info("ONLINE_DOWNLOAD_START title='{}' id={} libId={} file='{}' archiveEntry='{}' collection={}",
                safeBookTitle(book), book.getId(), safeLog(book.getLibId()), safeLog(book.getFileName()),
                safeLog(book.getArchiveEntry()), collectionId);

        return executor.submitCollectionScopedNetwork(LibraryOperationType.DOWNLOAD, () -> {
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
                            if (!isCurrentCollection(collectionId)) return;
                            applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.ceea793bc6") + book.getTitle() + UiText.text("ui.java.book_download_coordinator.0cafc3a4a6"));
                        });

                        if (!isCurrentCollection(collectionId)) {
                            throw new IllegalStateException(UiText.text("ui.error.collection_changed.book_download"));
                        }
                        BookId authoritativeId = BookId.fromString(book.getId());
                        BookDto effectiveBook = loadBookByIdUseCase.execute(authoritativeId)
                                .orElseThrow(() -> new IllegalStateException(
                                        UiText.format("ui.error.book_removed_expected_collection", authoritativeId)));
                        normalizeLegacyRemoteStorage(effectiveBook, collection);
                        if (!force) {
                            var existingNow = locateExistingLocal(effectiveBook, collection);
                            if (existingNow.isPresent()) {
                                effectiveBook.setLocal(true);
                                log.info("ONLINE_DOWNLOAD_SKIP_LOCAL_RACE title='{}' id={} path='{}'",
                                        safeBookTitle(effectiveBook), effectiveBook.getId(), existingNow.get());
                                return new DownloadCompletion(existingNow.get(), effectiveBook);
                            }
                        }
                        Path downloadedPath = downloadBookUseCase.execute(effectiveBook, collection, cancel, value -> {
                            long processed = Math.max(0L, Math.min(1000L, Math.round(value * 1000.0)));
                            operationCenter.accept(operationTitle, collectionId, OperationKind.BOOK_DOWNLOAD,
                                    OperationProgress.stage(operationId, OperationStage.BOOK_DOWNLOAD, true)
                                            .withProgress(processed, 1000)
                                            .withCurrentItem(safeBookTitle(effectiveBook)));
                            Platform.runLater(() -> {
                                if (!isCurrentCollection(collectionId)) return;
                                applicationState.getStatusBar().setProgress(value);
                                int percent = (int) Math.round(value * 100);
                                applicationState.getStatusBar().setStatusText(UiText.text("ui.java.book_download_coordinator.28e9bf28d4") + effectiveBook.getTitle() + " (" + percent + "%)");
                            });
                        }, force);
                        BookDto refreshed = loadBookByIdUseCase.execute(BookId.fromString(book.getId())).orElse(effectiveBook);
                        return new DownloadCompletion(downloadedPath, refreshed);
                    } finally {
                        if (acquired) downloadSlots.release();
                    }
                })
                .whenComplete((completion, error) -> {
                    active.remove(book.getId(), cancel);
                    long duration = System.currentTimeMillis() - startTime;
                    BookDto refreshed = error == null && completion != null ? completion.refreshed() : book;
                    // Complete the future only after the caller's DTO has authoritative storage metadata.
                    // External-open callbacks may run immediately when this stage completes and must not
                    // observe the pre-download remote folder/fileName.
                    if (error == null) {
                        copyStorageState(refreshed, book);
                        log.info("ONLINE_DOWNLOAD_DONE title='{}' id={} libId={} file='{}' archiveEntry='{}' durationMs={} path='{}'",
                                safeBookTitle(refreshed), refreshed.getId(), safeLog(refreshed.getLibId()),
                                safeLog(refreshed.getFileName()), safeLog(refreshed.getArchiveEntry()), duration,
                                completion == null || completion.path() == null ? "" : completion.path());
                        operationCenter.complete(operationId, UiText.text("ui.java.book_download_coordinator.a0c024460b") + Math.max(0, duration / 1000) + UiText.text("ui.java.book_download_coordinator.488d98cad9"));
                    } else {
                        Throwable operationFailure = unwrap(error);
                        if (UiExceptionSupport.isCancellation(operationFailure)) {
                            log.info("ONLINE_DOWNLOAD_CANCEL title='{}' id={} durationMs={}",
                                    safeBookTitle(book), book.getId(), duration);
                            operationCenter.cancel(operationId, UiText.text("ui.java.book_download_coordinator.b23355d41c"));
                        } else {
                            log.error("ONLINE_DOWNLOAD_FAIL title='{}' id={} libId={} file='{}' archiveEntry='{}' durationMs={} error={}",
                                    safeBookTitle(book), book.getId(), safeLog(book.getLibId()), safeLog(book.getFileName()),
                                    safeLog(book.getArchiveEntry()), duration, compactError(operationFailure));
                            operationCenter.fail(operationId, operationFailure);
                        }
                    }
                    Platform.runLater(() -> {
                        if (!isCurrentCollection(collectionId)) {
                            log.debug("Пропущено stale download UI callback для колекції {}", collectionId);
                            return;
                        }
                        applicationState.getStatusBar().setProgressVisible(false);
                        if (error == null) {
                            applicationState.getStatusBar().setTransientStatusText(UiText.text("ui.java.book_download_coordinator.a10fff9611") + refreshed.getTitle() + " (" + duration / 1000 + UiText.text("ui.java.book_download_coordinator.3023b67d72"));
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
                                log.error("Завантаження книги '{}' (id={}, libId={}) завершилося помилкою: {}",
                                        safeBookTitle(book), book.getId(), safeLog(book.getLibId()), compactError(cause));
                                if (showErrors) dialogService.showError(UiText.text("ui.import.stage.downloading"), userVisibleDownloadError(cause));
                            }
                        }
                    });
                })
                .thenApply(DownloadCompletion::path);
    }

    private record DownloadCompletion(Path path, BookDto refreshed) { }

    private java.util.Optional<Path> locateExistingLocal(BookDto book, Collection collection) {
        if (book == null || collection == null) return java.util.Optional.empty();
        java.util.Optional<Path> existing = bookResourcePort.locateBookFile(
                book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry());
        if (existing.isPresent()) {
            book.setLocal(true);
            return existing;
        }

        Path rootFolder = collection.getRootFolder();
        if (rootFolder == null) return java.util.Optional.empty();
        String currentRoot = rootFolder.toAbsolutePath().normalize().toString();

        // Stale catalogue/storage DTOs can retain an old/transient collection root. Always probe the
        // current active root before deciding that network I/O is required.
        if (!java.util.Objects.equals(currentRoot, book.getCollectionRoot())) {
            existing = bookResourcePort.locateBookFile(
                    book.getFileName(), book.getFolder(), currentRoot, book.getArchiveEntry());
            if (existing.isPresent()) {
                book.setCollectionRoot(currentRoot);
                book.setLocal(true);
                return existing;
            }
        }

        // Legacy online FB2 storage uses a generated archive path. Recompute it from authoritative
        // metadata so a stale remote DTO (e.g. fileName=729586.fb2) still discovers an already
        // downloaded <libId> <title>.fb2.zip before any HTTP request is started.
        if (isOnlineCollection(collection) && isFb2(book)) {
            String expectedFolder = LegacyOnlineBookLocation.archivePath(
                    primaryAuthorName(book), book.getTitle(), book.getLibId(), book.getFileName());
            existing = bookResourcePort.locateBookFile(
                    book.getFileName(), expectedFolder, currentRoot, book.getArchiveEntry());
            if (existing.isPresent()) {
                book.setCollectionRoot(currentRoot);
                book.setFolder(expectedFolder);
                book.setLocal(true);
                return existing;
            }
        }
        return java.util.Optional.empty();
    }

    private void normalizeLegacyRemoteStorage(BookDto book, Collection collection) {
        if (book == null || collection == null || collection.getId() == null || collection.getId().isBlank()) return;

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

    private static String safeLog(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ').replace('\n', ' ').trim();
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
            return CompletableFuture.failedFuture(new IllegalStateException(UiText.text("ui.error.collection_not_selected")));
        }
        return loadAuthoritative(book, expectedCollectionId).thenCompose(authoritative ->
                executor.submitCollectionScoped(LibraryOperationType.QUERY,
                                () -> removeLocalBookCopyUseCase.preview(authoritative, expectedCollectionId))
                        .thenCompose(preview -> confirmSingleRemoval(authoritative, preview)
                                .thenCompose(approved -> approved
                                        ? executor.submit(() -> removeLocalBookCopyUseCase.execute(authoritative, expectedCollectionId))
                                        : CompletableFuture.failedFuture(new java.util.concurrent.CancellationException(UiText.text("ui.java.book_download_coordinator.fd5c8520d7"))))))
                .whenComplete((count, error) -> Platform.runLater(() -> {
                    if (!isCurrentCollection(expectedCollectionId)) {
                        log.debug("Пропущено stale local-copy UI callback для колекції {}", expectedCollectionId);
                        return;
                    }
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
            return CompletableFuture.failedFuture(new IllegalStateException(UiText.text("ui.error.collection_not_selected")));
        }
        return executor.submitCollectionScoped(LibraryOperationType.QUERY, () -> {
            LinkedHashMap<String, PlannedRemoval> unique = new LinkedHashMap<>();
            for (BookId id : bookIds.stream().distinct().toList()) {
                BookDto book = loadBookByIdUseCase.execute(id).orElse(null);
                if (book == null) continue;
                var preview = removeLocalBookCopyUseCase.preview(book, expectedCollectionId);
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
            if (!isCurrentCollection(expectedCollectionId)) {
                log.debug("Пропущено stale batch local-copy UI callback для колекції {}", expectedCollectionId);
                return;
            }
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
        return UiExecutor.supplyOnUiThread(() -> dialogService.showConfirmation(title, header, details));
    }

    private static String displayPath(Path path) {
        return path == null ? UiText.text("ui.java.book_download_coordinator.73e081d220") : path.toString();
    }

    private record PlannedRemoval(BookDto book, RemoveLocalBookCopyUseCase.RemovalPreview preview) { }

    private boolean isCurrentCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) return false;
        String current = applicationState.getCurrentLibraryCollectionId();
        return expectedCollectionId.equals(current);
    }

    private void requireCurrentCollection(String expectedCollectionId, String operation) {
        if (!isCurrentCollection(expectedCollectionId)) {
            throw new IllegalStateException(UiText.text(operation));
        }
    }

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