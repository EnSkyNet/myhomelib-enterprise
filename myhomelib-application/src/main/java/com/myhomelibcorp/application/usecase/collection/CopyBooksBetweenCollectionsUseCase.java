package com.myhomelibcorp.application.usecase.collection;

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
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.shared.util.FileNameSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    public record Result(int copied, int failed, List<String> errors) { }

    public Result execute(List<BookId> ids, String targetCollectionId) {
        List<BookId> requested = normalize(ids);
        if (requested.isEmpty()) return new Result(0, 0, List.of());

        Collection source = lifecycle.getCurrentCollection();
        if (source == null) throw new IllegalStateException("Активну колекцію не вибрано");
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
        try {
            boolean reusableTargetIndex = lifecycle.initializeCollection(target, false);
            if (!reusableTargetIndex) {
                // Selective updates are only valid on top of a complete target index. Rebuild once
                // when the target index is absent/dirty; clean targets remain incremental.
                lifecycle.rebuildSearchIndex();
            }
            Files.createDirectories(targetRoot.resolve(MANAGED_FOLDER));

            Set<BookId> alreadyPresent = new LinkedHashSet<>();
            for (Book existing : books.findByIds(new ArrayList<>(sourceBooks.keySet()))) {
                if (existing != null && existing.getId() != null) alreadyPresent.add(existing.getId());
            }

            List<Book> saveBatch = new ArrayList<>(SAVE_BATCH);
            Map<BookId, Path> copiedPaths = new LinkedHashMap<>();
            for (Book sourceBook : sourceBooks.values()) {
                if (alreadyPresent.contains(sourceBook.getId())) continue;
                try {
                    Path destination = copyPhysicalBook(sourceBook, targetRoot);
                    Book targetBook = copyMetadataToTarget(sourceBook, targetRoot, destination);
                    saveBatch.add(targetBook);
                    copiedPaths.put(targetBook.getId(), destination);

                    if (saveBatch.size() >= SAVE_BATCH) {
                        copied += flushBatch(source, target, saveBatch, copiedPaths, errors);
                    }
                } catch (Exception e) {
                    errors.add(sourceBook.getId() + ": " + safeMessage(e));
                }
            }
            if (!saveBatch.isEmpty()) copied += flushBatch(source, target, saveBatch, copiedPaths, errors);

        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося скопіювати книги: " + safeMessage(e), e);
        } finally {
            try {
                // Per-collection Lucene is reused when its freshness marker still matches source DB.
                lifecycle.initializeCollection(source, true);
            } catch (Exception e) {
                log.error("Не вдалося повернутися до вихідної колекції", e);
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
                           Map<BookId, Path> copiedPaths,
                           List<String> errors) {
        if (batch.isEmpty()) return 0;
        List<Book> attempted = List.copyOf(batch);
        Map<BookId, Path> attemptedPaths = new LinkedHashMap<>(copiedPaths);
        try {
            return persistBatch(source, target, attempted, attemptedPaths);
        } catch (PostCommitSearchSyncException failure) {
            // DB rows + user state are already committed. Deleting their files here would create
            // broken catalog rows. Keep the physical books and fail loudly; the stale Lucene marker
            // will force a rebuild on the next target activation.
            log.error("Lucene sync failed after committed copy batch; copied files are retained", failure);
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

    private int persistBatch(Collection source, Collection target, List<Book> batch, Map<BookId, Path> copiedPaths) {
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
            Path unused = copiedPaths.get(book.getId());
            if (unused != null) cleanupCopiedFiles(List.of(unused));
        }
        if (!saved.isEmpty()) {
            List<BookId> ids = saved.stream().map(Book::getId).toList();
            if (!searchIndexSynchronizer.synchronizeSafelyNow(ids)) {
                throw new PostCommitSearchSyncException(
                        "Target DB commit succeeded, but Lucene synchronization failed for " + ids.size() + " books");
            }
        }
        return saved.size();
    }


    private static void cleanupCopiedFiles(Iterable<Path> paths) {
        if (paths == null) return;
        for (Path path : paths) {
            if (path == null) continue;
            try {
                Files.deleteIfExists(path);
            } catch (Exception cleanupFailure) {
                log.warn("Не вдалося прибрати незбережений файл {}", path, cleanupFailure);
            }
        }
    }

    private Path copyPhysicalBook(Book book, Path targetRoot) throws Exception {
        String sourceName = book.getArchiveEntry();
        if (sourceName == null || sourceName.isBlank()) sourceName = book.getFileName();
        String ext = FileNameSupport.extension(sourceName);
        String extension = ext.isBlank() ? ".book" : "." + ext;
        String id = book.getId().asString();
        String title = safeFileName(book.getTitle());
        String fileName = id + "-" + title + extension;
        Path destination = targetRoot.resolve(MANAGED_FOLDER).resolve(fileName).toAbsolutePath().normalize();
        Path temp = destination.resolveSibling(destination.getFileName() + ".part");

        Files.createDirectories(destination.getParent());
        try (InputStream input = resources.readBookData(book)
                .orElseThrow(() -> new IllegalStateException("Файл книги недоступний"))) {
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        AtomicFileSupport.moveReplacing(temp, destination);
        return destination;
    }

    private static Book copyMetadataToTarget(Book source, Path targetRoot, Path destination) throws Exception {
        long size = Files.size(destination);
        BookFile file = new BookFile(
                destination.getFileName().toString(),
                MANAGED_FOLDER,
                "",
                size,
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

    private static List<BookId> normalize(List<BookId> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        LinkedHashSet<BookId> unique = new LinkedHashSet<>();
        for (BookId id : ids) if (id != null) unique.add(id);
        return new ArrayList<>(unique);
    }

    private static String safeFileName(String value) {
        if (value == null || value.isBlank()) return "book";
        String cleaned = value.replaceAll("[<>:\"/\\\\|?*]", "_")
                .replaceAll("\\s+", " ").trim();
        if (cleaned.length() > 120) cleaned = cleaned.substring(0, 120).trim();
        return cleaned.isBlank() ? "book" : cleaned;
    }

    private static final class PostCommitSearchSyncException extends RuntimeException {
        private PostCommitSearchSyncException(String message) {
            super(message);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? null : error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
