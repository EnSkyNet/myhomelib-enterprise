package com.myhomelibcorp.application.usecase.download;

import com.myhomelibcorp.shared.util.ThrowableMessages;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.application.port.out.download.OnlineBookDownloadPort;
import com.myhomelibcorp.application.port.out.download.DownloadQueuePort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.application.content.indexing.ContentIndexAutoSchedulingService;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class DownloadBookUseCase {
    private final OnlineBookDownloadPort downloadPort;
    private final DownloadQueuePort downloadQueuePort;
    private final BookCommandRepository bookCommandRepository;
    private final CatalogUpdateTrackingPort catalogUpdateTrackingPort;
    private final StatisticsRepository statisticsRepository;
    private final SearchIndexSynchronizer searchIndexSynchronizer;
    private final ContentIndexAutoSchedulingService contentIndexScheduling;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final LibraryOperationCoordinator operationCoordinator;
    private final ExecutorPort executorPort;

    public Path execute(BookDto book, Collection collection, AtomicBoolean cancelFlag, DoubleConsumer progress) throws Exception {
        return execute(book, collection, cancelFlag, progress, false);
    }

    public Path execute(BookDto book, Collection collection, AtomicBoolean cancelFlag, DoubleConsumer progress,
                        boolean forceRefresh) throws Exception {
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.DOWNLOAD);
        try {
            Path result = executeLocked(book, collection, cancelFlag, progress, forceRefresh);
            lease.markCompleted("");
            return result;
        } catch (java.util.concurrent.CancellationException cancelled) {
            lease.markCancelled(cancelled.getMessage());
            throw cancelled;
        } catch (Exception failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private Path executeLocked(BookDto book, Collection collection, AtomicBoolean cancelFlag, DoubleConsumer progress,
                               boolean forceRefresh) throws Exception {
        if (book == null) throw new IllegalArgumentException("Book is required");
        if (collection == null) throw new IllegalStateException("Активну колекцію не вибрано");
        if (collection.getId() == null || collection.getId().isBlank()) throw new IllegalStateException("Колекція не має stable ID");
        Collection active = collectionLifecyclePort.getCurrentCollection();
        if (active == null || active.getId() == null || !collection.getId().equals(active.getId())) {
            throw new IllegalStateException("Активна колекція змінилася до початку завантаження");
        }
        BookId bookId = BookId.fromString(book.getId());
        AtomicBoolean cancel = cancelFlag == null ? new AtomicBoolean(false) : cancelFlag;
        String archiveIdentity = physicalIdentity(book);
        String resumeHint = archiveIdentity.isBlank() ? null : archiveIdentity + ".part";

        downloadQueuePort.markPending(collection.getId(), bookId.asString(), archiveIdentity, resumeHint);
        downloadQueuePort.markInProgress(collection.getId(), bookId.asString());
        try {
            var result = downloadPort.download(book, collection, cancel, progress == null ? v -> {} : progress, forceRefresh);
            // updateStorage is the authoritative commit for a successful physical download. Everything
            // below it is derived/bookkeeping state and must not turn an already-local book into a false
            // FAILED download if a secondary store/index is temporarily unavailable.
            bookCommandRepository.updateStorage(
                    bookId, result.root().toString(), result.folder(), result.fileName(), result.archiveEntry(), true);
            markDownloadedBaselineSafely(bookId);
            synchronizeDerivedIndexesSafely(collection.getId(), bookId);
            markDownloadCompletedSafely(collection.getId(), bookId, result.physicalPath());
            invalidateStatisticsSafely();
            return result.physicalPath();
        } catch (Exception error) {
            try {
                if (cancel.get() || isCancellation(error)) {
                    downloadQueuePort.markCancelled(collection.getId(), bookId.asString(), resumeHint);
                } else {
                    downloadQueuePort.markFailed(collection.getId(), bookId.asString(),
                            SensitiveDataSanitizer.sanitizeText(ThrowableMessages.rootMessage(error)), resumeHint);
                }
            } catch (RuntimeException queueFailure) {
                // Queue persistence is diagnostic/restart metadata. Preserve the actual download/storage
                // failure as the primary exception instead of masking it with a secondary metadata error.
                error.addSuppressed(queueFailure);
                log.warn("Не вдалося зберегти terminal download-queue state для {}", bookId, queueFailure);
            }
            throw error;
        }
    }

    private void markDownloadedBaselineSafely(BookId bookId) {
        try {
            catalogUpdateTrackingPort.markDownloadedBaseline(bookId);
        } catch (RuntimeException error) {
            log.warn("Книгу вже завантажено, але не вдалося оновити catalog download baseline для {}", bookId, error);
        }
    }

    private void synchronizeDerivedIndexesSafely(String collectionId, BookId bookId) {
        if (operationCoordinator.isOperationActive(LibraryOperationType.INDEX)) {
            deferDerivedIndexSynchronization(collectionId, bookId);
            return;
        }
        synchronizeSearchIndexNowSafely(bookId);
        synchronizeContentIndexSafely(collectionId, bookId);
    }

    /**
     * A download is allowed to join an already-running full Lucene rebuild. The downloaded file
     * and catalog row must become visible immediately, while the selective Lucene repair waits
     * off the caller thread for the rebuild's synchronized writer section to finish.
     *
     * <p>The extra DOWNLOAD lease deliberately keeps the same INDEX-root collection barrier alive
     * after the rebuild worker releases its own lease, so a collection SWITCH cannot race the
     * deferred repair into a different dynamic repository/index.</p>
     */
    private void deferDerivedIndexSynchronization(String collectionId, BookId bookId) {
        LibraryOperationCoordinator.Lease deferredLease =
                operationCoordinator.acquireDetachedShared(LibraryOperationType.DOWNLOAD);
        try {
            executorPort.execute(() -> {
                try {
                    synchronizeSearchIndexNowSafely(bookId);
                    // Full-text rebuild owns its own Lucene write lock. Scheduling here may wait
                    // for that lock, but it happens off the user download thread. Queue stale-hold
                    // reference counting guarantees that the rebuild cannot publish a false READY
                    // state while this committed download is still being repaired.
                    synchronizeContentIndexSafely(collectionId, bookId);
                    deferredLease.markCompleted("Deferred derived-index synchronization completed");
                } finally {
                    deferredLease.close();
                }
            });
        } catch (RuntimeException schedulingFailure) {
            deferredLease.close();
            log.warn("Не вдалося відкласти синхронізацію похідних індексів для {}; виконую синхронно",
                    bookId, schedulingFailure);
            synchronizeSearchIndexNowSafely(bookId);
            synchronizeContentIndexSafely(collectionId, bookId);
        }
    }

    private void synchronizeSearchIndexNowSafely(BookId bookId) {
        try {
            if (!searchIndexSynchronizer.synchronizeSafelyNow(java.util.List.of(bookId))) {
                log.warn("Книгу вже завантажено, але Lucene-індекс лишився stale для {}", bookId);
            }
        } catch (RuntimeException error) {
            log.warn("Книгу вже завантажено, але не вдалося синхронізувати Lucene для {}", bookId, error);
        }
    }

    private void markDownloadCompletedSafely(String collectionId, BookId bookId, Path physicalPath) {
        try {
            downloadQueuePort.markCompleted(collectionId, bookId.asString(), physicalPath);
        } catch (RuntimeException error) {
            log.warn("Книгу вже завантажено, але не вдалося позначити download queue як COMPLETED для {}", bookId, error);
        }
    }

    private void synchronizeContentIndexSafely(String collectionId, BookId bookId) {
        try {
            var result = contentIndexScheduling.schedule(collectionId, new ImportChangeSet(
                    java.util.Set.of(), java.util.Set.of(bookId.asString()), java.util.Set.of(), true));
            if (result.rebuildRequired()) {
                log.info("Full-text index remains stale after download: {}", result.detail());
            }
        } catch (RuntimeException error) {
            try { contentIndexScheduling.markStale(collectionId); }
            catch (RuntimeException markerFailure) { error.addSuppressed(markerFailure); }
            log.error("Не вдалося поставити завантажену книгу в чергу full-text індексації", error);
        }
    }

    private void invalidateStatisticsSafely() {
        try {
            statisticsRepository.invalidate();
        } catch (RuntimeException error) {
            // The book/local-state transaction is already complete. Do not report a false
            // download failure because a derived statistics cache could not be invalidated.
            log.warn("Не вдалося інвалідувати кеш статистики після завантаження", error);
        }
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof java.util.concurrent.CancellationException) return true;
            String name = current.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("cancel") || message.contains("скасовано") || message.contains("cancelled") || message.contains("canceled")) return true;
            current = current.getCause();
        }
        return false;
    }


    private static String physicalIdentity(BookDto book) {
        String folder = clean(book.getFolder());
        String file = clean(book.getFileName());
        if (book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()) {
            if (!folder.isBlank()) return folder;
            return file;
        }
        return folder.isBlank() ? file : folder + "/" + file;
    }

    private static String clean(String value) {
        if (value == null) return "";
        String v = value.replace('\\', '/').trim();
        while (v.startsWith("/")) v = v.substring(1);
        return v;
    }
}
