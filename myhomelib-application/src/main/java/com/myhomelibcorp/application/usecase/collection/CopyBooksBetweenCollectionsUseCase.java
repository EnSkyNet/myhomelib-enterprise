package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.util.BookIdListSupport;
import com.myhomelibcorp.application.content.indexing.ContentIndexAutoSchedulingService;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.collection.BookUserStateTransferPort;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.util.FileNameSupport;
import com.myhomelibcorp.shared.util.SafeFileNameSupport;
import com.myhomelibcorp.application.util.SafeFilePublication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Copies selected books between independent collection databases without reparsing metadata. */
@RequiredArgsConstructor
@Slf4j
public class CopyBooksBetweenCollectionsUseCase {
    private static final int QUERY_BATCH = 400;
    private static final int SAVE_BATCH = 100;
    private static final String MANAGED_FOLDER = ".myhomelib-copied";

    private final BookQueryRepository books;
    private final CollectionRepository collections;
    private final BookResourcePort resources;
    private final CollectionLifecycleService lifecycle;
    private final BookSaver bookSaver;
    private final BookUserStateTransferPort userStateTransfer;
    private final SearchIndexSynchronizer searchIndexSynchronizer;
    private final ContentIndexAutoSchedulingService contentIndexScheduling;

    public record Result(int copied, int failed, List<String> errors) { }

    public Result execute(List<BookId> ids, String targetCollectionId) {
        Collection current = lifecycle.getCurrentCollection();
        String expectedSourceCollectionId = current == null ? null : current.getId();
        return execute(ids, targetCollectionId, expectedSourceCollectionId);
    }

    /**
     * Executes a copy only while the same source collection that initiated the UI request remains active.
     * The caller must hold the exclusive COPY lifecycle barrier before enqueueing asynchronous work.
     */
    public Result execute(List<BookId> ids, String targetCollectionId, String expectedSourceCollectionId) {
        List<BookId> requested = BookIdListSupport.stableDistinctIgnoringNulls(ids);
        if (requested.isEmpty()) return new Result(0, 0, List.of());

        Collection source = lifecycle.getCurrentCollection();
        if (source == null) throw new IllegalStateException("Активну колекцію не вибрано");
        if (expectedSourceCollectionId == null || !expectedSourceCollectionId.equals(source.getId())) {
            throw new IllegalStateException("Вихідна колекція змінилася до початку копіювання");
        }
        Collection target = collections.findById(targetCollectionId)
                .orElseThrow(() -> new IllegalArgumentException("Цільову колекцію не знайдено"));
        if (source.getId().equals(target.getId())) throw new IllegalArgumentException("Виберіть іншу колекцію");
        Path targetRoot = target.getRootFolder();
        if (targetRoot == null) throw new IllegalStateException("Цільова колекція не має кореневої папки");

        // Source rows must be materialized before switching the dynamic collection DataSource.
        // The UI selection is paged/bounded; repository lookup itself is chunk-safe.
        Map<BookId, Book> sourceBooks = loadRequestedBooks(requested);
        List<String> errors = new ArrayList<>();
        for (BookId id : requested) if (!sourceBooks.containsKey(id)) errors.add(id + ": книгу не знайдено");

        int copied = 0;
        Throwable primaryFailure = null;
        try {
            boolean reusableTargetIndex = lifecycle.initializeCollection(target, false);
            if (!reusableTargetIndex) {
                // COPY owns the lifecycle barrier from before the worker was queued. Rebuild through
                // the synchronizer directly so we do not try to acquire a second INDEX root lease.
                if (!searchIndexSynchronizer.rebuildSafelyNow()) {
                    throw new IllegalStateException("Не вдалося підготувати пошуковий індекс цільової колекції");
                }
            }
            SafeFilePublication.createDirectoriesNoLinks(targetRoot.resolve(MANAGED_FOLDER), "Copied-books managed folder");

            Set<BookId> alreadyPresent = new LinkedHashSet<>();
            for (Book existing : books.findByIds(new ArrayList<>(sourceBooks.keySet()))) {
                if (existing != null && existing.getId() != null) alreadyPresent.add(existing.getId());
            }

            List<Book> saveBatch = new ArrayList<>(SAVE_BATCH);
            Map<BookId, CopiedFile> copiedPaths = new LinkedHashMap<>();
            for (Book sourceBook : sourceBooks.values()) {
                if (alreadyPresent.contains(sourceBook.getId())) continue;
                try {
                    CopiedFile copiedFile = copyPhysicalBook(sourceBook, targetRoot);
                    Book targetBook = copyMetadataToTarget(sourceBook, targetRoot, copiedFile);
                    saveBatch.add(targetBook);
                    copiedPaths.put(targetBook.getId(), copiedFile);

                    if (saveBatch.size() >= SAVE_BATCH) {
                        copied += flushBatch(source, target, saveBatch, copiedPaths, errors);
                    }
                } catch (Exception e) {
                    errors.add(sourceBook.getId() + ": " + safeMessage(e));
                }
            }
            if (!saveBatch.isEmpty()) copied += flushBatch(source, target, saveBatch, copiedPaths, errors);

        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } catch (Exception failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "Не вдалося скопіювати книги: " + safeMessage(failure), failure);
            primaryFailure = wrapped;
            throw wrapped;
        } finally {
            try {
                // Returning to the source collection is part of COPY's lifecycle contract, not a
                // best-effort UI convenience. A successful target commit must never be reported as
                // a successful COPY while the application remains attached to the target collection.
                lifecycle.initializeCollection(source, true);
            } catch (RuntimeException | Error restoreFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(restoreFailure);
                } else {
                    throw new IllegalStateException(
                            "Копіювання завершилося, але не вдалося повернутися до вихідної колекції; "
                                    + "цільові зміни могли вже бути зафіксовані",
                            restoreFailure);
                }
            }
        }
        return new Result(copied, errors.size(), List.copyOf(errors));
    }

    private Map<BookId, Book> loadRequestedBooks(List<BookId> ids) {
        Map<BookId, Book> result = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += QUERY_BATCH) {
            List<BookId> batch = ids.subList(from, Math.min(ids.size(), from + QUERY_BATCH));
            for (Book book : books.findByIds(batch)) {
                if (book != null && book.getId() != null) result.put(book.getId(), book);
            }
        }
        return result;
    }


    private int flushBatch(Collection source,
                           Collection target,
                           List<Book> batch,
                           Map<BookId, CopiedFile> copiedPaths,
                           List<String> errors) {
        if (batch.isEmpty()) return 0;
        List<Book> attempted = List.copyOf(batch);
        Map<BookId, CopiedFile> attemptedPaths = new LinkedHashMap<>(copiedPaths);
        try {
            return persistBatch(source, target, attempted, attemptedPaths);
        } catch (PostCommitDerivedIndexSyncException failure) {
            // DB rows + user state are already committed. Deleting their files here would create
            // broken catalog rows. Keep the physical books and fail loudly; derived indexes are
            // either marked stale or will be validated/rebuilt on the next target activation.
            log.error("Derived-index sync failed after committed copy batch; copied files are retained", failure);
            throw failure;
        } catch (Exception failure) {
            cleanupCopiedFiles(attemptedPaths.values());
            String message = safeMessage(failure);
            for (Book book : attempted) errors.add(book.getId() + ": " + message);
            log.error("Не вдалося атомарно зберегти batch із {} книг та user state", attempted.size(), failure);
            return 0;
        } finally {
            batch.clear();
            copiedPaths.clear();
        }
    }

    private int persistBatch(Collection source, Collection target, List<Book> batch, Map<BookId, CopiedFile> copiedPaths) {
        // The target book row and user state share one SQLite transaction. Lucene is derived state
        // and is synchronized only after that transaction has successfully committed.
        List<Book> saved = bookSaver.saveBatchReturningSaved(
                batch,
                false,
                DuplicatePolicy.SKIP,
                persisted -> userStateTransfer.transferCopiedBookState(
                        source, target, persisted.stream().map(Book::getId).toList()));
        Set<BookId> savedIds = saved.stream().map(Book::getId).collect(java.util.stream.Collectors.toSet());
        for (Book book : batch) {
            if (savedIds.contains(book.getId())) continue;
            CopiedFile unused = copiedPaths.get(book.getId());
            if (unused != null) cleanupCopiedFiles(List.of(unused));
        }
        if (!saved.isEmpty()) {
            List<BookId> ids = saved.stream().map(Book::getId).toList();
            // The full-text task processor is bound to the active collection DataSource. The target
            // collection becomes inactive again when this use case returns, so do not queue exact
            // tasks here; persistently mark its content index stale instead. This prevents a
            // previously READY target index from silently omitting the newly copied books.
            try {
                contentIndexScheduling.markStale(target.getId());
            } catch (RuntimeException failure) {
                throw new PostCommitDerivedIndexSyncException(
                        "Target DB commit succeeded, but the full-text index could not be marked stale", failure);
            }
            if (!searchIndexSynchronizer.synchronizeSafelyNow(ids)) {
                throw new PostCommitDerivedIndexSyncException(
                        "Target DB commit succeeded, but Lucene synchronization failed for " + ids.size() + " books");
            }
        }
        return saved.size();
    }


    private static void cleanupCopiedFiles(Iterable<CopiedFile> files) {
        if (files == null) return;
        for (CopiedFile copied : files) {
            if (copied == null || copied.path() == null) continue;
            try {
                if (!SafeFilePublication.removeIfOwned(copied.path(), copied.sha256(), "Copied-book rollback")) {
                    log.warn("Не вдалося підтверджено прибрати незбережений файл {}", copied.path());
                }
            } catch (Exception cleanupFailure) {
                log.warn("Не вдалося безпечно прибрати незбережений файл {}; файл збережено як recovery evidence",
                        copied.path(), cleanupFailure);
            }
        }
    }

    private CopiedFile copyPhysicalBook(Book book, Path targetRoot) throws Exception {
        String sourceName = book.getArchiveEntry();
        if (sourceName == null || sourceName.isBlank()) sourceName = book.getFileName();
        String ext = FileNameSupport.extension(sourceName);
        String safeExtension = SafeFileNameSupport.sanitizeComponent(ext, 24);
        String extension = safeExtension.isBlank() ? ".book" : "." + safeExtension;
        String id = book.getId().asString();
        String title = safeFileName(book.getTitle());
        String fileName = SafeFileNameSupport.sanitizeComponent(id + "-" + title + extension, 240);
        if (fileName.isBlank()) fileName = id + ".book";
        Path destination = targetRoot.resolve(MANAGED_FOLDER).resolve(fileName).toAbsolutePath().normalize();
        Path parent = SafeFilePublication.createDirectoriesNoLinks(destination.getParent(), "Copied-book destination");
        Path workspace = SafeFilePublication.createPrivateWorkspace(parent, ".mhl-copy-", "Copied-book staging workspace");
        Path staged = workspace.resolve("book" + extension);
        try {
            try (InputStream input = resources.readBookData(book)
                    .orElseThrow(() -> new IllegalStateException("Файл книги недоступний"))) {
                SafeFilePublication.copyFresh(input, staged, "Copied-book staged file");
            }
            SafeFilePublication.FileSnapshot stagedSnapshot =
                    SafeFilePublication.snapshot(staged, "Copied-book staged file");
            SafeFilePublication.moveNoReplace(staged, destination, "Copied-book publication");
            SafeFilePublication.FileSnapshot publishedSnapshot =
                    SafeFilePublication.snapshot(destination, "Copied-book published file");
            if (!stagedSnapshot.sha256().equalsIgnoreCase(publishedSnapshot.sha256())
                    || stagedSnapshot.size() != publishedSnapshot.size()) {
                throw new java.io.IOException("Copied-book publication fingerprint mismatch: " + destination);
            }
            return new CopiedFile(destination, publishedSnapshot.sha256(), publishedSnapshot.size());
        } finally {
            try {
                SafeFilePublication.cleanupPrivateWorkspace(workspace);
            } catch (Exception cleanupFailure) {
                log.warn("Не вдалося прибрати staging workspace {}; збережено для recovery", workspace, cleanupFailure);
            }
        }
    }

    private static Book copyMetadataToTarget(Book source, Path targetRoot, CopiedFile copiedFile) {
        Path destination = copiedFile.path();
        BookFile file = new BookFile(
                destination.getFileName().toString(),
                MANAGED_FOLDER,
                "",
                copiedFile.size(),
                targetRoot.toAbsolutePath().normalize().toString());
        return Book.builder()
                .id(source.getId())
                .title(source.getTitle())
                .authors(new ArrayList<>(source.getAuthors()))
                .genres(new ArrayList<>(source.getGenres()))
                .series(source.getSeries())
                .sequenceNumber(source.getSequenceNumber())
                .metadata(source.getMetadata())
                .file(file)
                .cover(source.getCover())
                .updateDate(source.getUpdateDate())
                .createdAt(source.getCreatedAt())
                .deleted(false)
                .local(true)
                .build();
    }


    private static String safeFileName(String value) {
        String cleaned = SafeFileNameSupport.sanitizeComponent(value, 120);
        return cleaned.isBlank() ? "book" : cleaned;
    }


    private record CopiedFile(Path path, String sha256, long size) { }
    private static final class PostCommitDerivedIndexSyncException extends IllegalStateException {
        private PostCommitDerivedIndexSyncException(String message) { super(message); }
        private PostCommitDerivedIndexSyncException(String message, Throwable cause) { super(message, cause); }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
