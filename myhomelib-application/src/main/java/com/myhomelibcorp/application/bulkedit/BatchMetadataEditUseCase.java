package com.myhomelibcorp.application.bulkedit;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.history.OperationHistoryPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * MHL-111 local batch editor: 10k+ selections are processed in bounded chunks inside one collection transaction.
 * MHL-112 snapshots are journaled in that same transaction so cancellation/failure cannot leave orphan history.
 */
@Service
public class BatchMetadataEditUseCase {
    public static final int MAX_SELECTED_BOOKS = 100_000;
    public static final int PREVIEW_SAMPLE_SIZE = 25;
    public static final int CHUNK_SIZE = 250;

    private final BookQueryRepository books;
    private final BookCommandRepository commands;
    private final GenreRepository genres;
    private final OperationHistoryPort history;
    private final SearchIndexSynchronizer searchIndex;
    private final LibraryOperationCoordinator operations;
    private final CollectionLifecyclePort collections;
    private final TransactionTemplate transaction;
    private final int historyRetention;

    public BatchMetadataEditUseCase(
            BookQueryRepository books,
            BookCommandRepository commands,
            GenreRepository genres,
            OperationHistoryPort history,
            SearchIndexSynchronizer searchIndex,
            LibraryOperationCoordinator operations,
            CollectionLifecyclePort collections,
            @Qualifier("collectionTransactionTemplate") TransactionTemplate transaction,
            @Value("${myhomelib.bulk-edit.history-retention:20}") int historyRetention) {
        this.books = Objects.requireNonNull(books);
        this.commands = Objects.requireNonNull(commands);
        this.genres = Objects.requireNonNull(genres);
        this.history = Objects.requireNonNull(history);
        this.searchIndex = Objects.requireNonNull(searchIndex);
        this.operations = Objects.requireNonNull(operations);
        this.collections = Objects.requireNonNull(collections);
        this.transaction = Objects.requireNonNull(transaction);
        this.historyRetention = Math.max(1, Math.min(200, historyRetention));
    }

    public BatchMetadataEditPreview preview(String collectionId, List<BookId> selected,
                                             List<BatchMetadataEditRule> rules) {
        List<BookId> ids = normalizeIds(selected);
        var plan = BatchMetadataEditEngine.prepare(rules);
        if (ids.isEmpty()) return new BatchMetadataEditPreview(0, List.of());
        try (var lease = operations.acquire(LibraryOperationType.UPDATE)) {
            requireCollection(collectionId);
            List<BookId> sampleIds = ids.subList(0, Math.min(ids.size(), PREVIEW_SAMPLE_SIZE));
            Map<BookId, Book> current = orderedMap(books.findByIds(sampleIds));
            List<BatchMetadataPreviewItem> sample = new ArrayList<>(sampleIds.size());
            for (BookId id : sampleIds) {
                Book book = requireBook(current, id);
                var before = snapshot(book);
                var after = plan.apply(before);
                sample.add(new BatchMetadataPreviewItem(id, before, after,
                        BatchMetadataEditEngine.changedFields(before, after)));
            }
            return new BatchMetadataEditPreview(ids.size(), sample);
        }
    }

    public BatchMetadataEditResult execute(String collectionId, List<BookId> selected,
                                            List<BatchMetadataEditRule> rules,
                                            AtomicBoolean cancelled,
                                            Consumer<BatchMetadataEditProgress> progress) {
        Objects.requireNonNull(cancelled, "cancelled");
        List<BookId> ids = normalizeIds(selected);
        var plan = BatchMetadataEditEngine.prepare(rules);
        if (ids.isEmpty()) return new BatchMetadataEditResult("", 0, 0, false);
        if (cancelled.get()) throw new CancellationException("Batch metadata edit cancelled");
        String operationId = UUID.randomUUID().toString();
        String summary = summarize(rules);

        try (var lease = operations.acquire(LibraryOperationType.UPDATE)) {
            requireCollection(collectionId);
            Execution execution = transaction.execute(status -> {
                history.beginBulkOperation(operationId, summary, ids.size(), List.copyOf(rules));
                List<BookId> changedIds = new ArrayList<>();
                int sequence = 0;
                int processed = 0;
                for (int from = 0; from < ids.size(); from += CHUNK_SIZE) {
                    checkCancelled(cancelled);
                    requireCollection(collectionId);
                    List<BookId> chunk = ids.subList(from, Math.min(ids.size(), from + CHUNK_SIZE));
                    Map<BookId, Book> current = orderedMap(books.findByIds(chunk));
                    List<Book> updates = new ArrayList<>();
                    List<BatchMetadataChange> changes = new ArrayList<>();
                    Map<String, Genre> genreCache = new LinkedHashMap<>();
                    for (BookId id : chunk) {
                        checkCancelled(cancelled);
                        Book book = requireBook(current, id);
                        var before = snapshot(book);
                        var after = plan.apply(before);
                        if (before.equals(after)) continue;
                        Book updated = restoreEditable(book, after, genreCache);
                        updates.add(updated);
                        changes.add(new BatchMetadataChange(id, before, snapshot(updated)));
                        changedIds.add(id);
                    }
                    if (!changes.isEmpty()) {
                        history.appendBulkChanges(operationId, sequence, changes);
                        sequence += changes.size();
                        commands.saveBatch(updates);
                    }
                    processed += chunk.size();
                    publish(progress, new BatchMetadataEditProgress(processed, ids.size()));
                }
                checkCancelled(cancelled);
                history.completeOperation(operationId, changedIds.size());
                history.prune(historyRetention);
                if (!changedIds.isEmpty()) searchIndex.synchronizeAfterCommit(changedIds);
                return new Execution(changedIds.size(), !changedIds.isEmpty());
            });
            if (execution == null) throw new IllegalStateException("Batch metadata transaction returned no result");
            return new BatchMetadataEditResult(operationId, ids.size(), execution.changedCount(), execution.searchScheduled());
        }
    }

    /** Restart-safe inverse operation. Only the newest undoable shared-journal entry may be reverted. */
    public BatchMetadataEditResult undo(String collectionId, String operationId,
                                         AtomicBoolean cancelled,
                                         Consumer<BatchMetadataEditProgress> progress) {
        if (operationId == null || operationId.isBlank()) throw new IllegalArgumentException("operationId is required");
        Objects.requireNonNull(cancelled, "cancelled");
        if (cancelled.get()) throw new CancellationException("Batch metadata undo cancelled");

        try (var lease = operations.acquire(LibraryOperationType.UPDATE)) {
            requireCollection(collectionId);
            UndoExecution execution = transaction.execute(status -> {
                history.requireLatestUndoable(operationId);
                int total = history.countBulkChanges(operationId);
                if (total <= 0) throw new IllegalStateException("Bulk operation has no reversible changes: " + operationId);
                List<BookId> restoredIds = new ArrayList<>(Math.min(total, MAX_SELECTED_BOOKS));
                Map<String, Genre> genreCache = new LinkedHashMap<>();
                int processed = 0;
                while (processed < total) {
                    checkCancelled(cancelled);
                    requireCollection(collectionId);
                    List<BatchMetadataChange> changes = history.loadBulkChanges(operationId, processed, CHUNK_SIZE);
                    if (changes.isEmpty()) throw new IllegalStateException("Bulk history is incomplete at offset " + processed);
                    List<BookId> ids = changes.stream().map(BatchMetadataChange::bookId).toList();
                    Map<BookId, Book> current = orderedMap(books.findByIds(ids));
                    List<Book> updates = new ArrayList<>(changes.size());
                    for (BatchMetadataChange change : changes) {
                        Book book = requireBook(current, change.bookId());
                        BatchMetadataEditableSnapshot now = snapshot(book);
                        if (!now.equals(change.after())) {
                            throw new IllegalStateException("Book changed after batch operation; undo refused: " + change.bookId());
                        }
                        updates.add(restoreEditable(book, change.before(), genreCache));
                        restoredIds.add(change.bookId());
                    }
                    commands.saveBatch(updates);
                    processed += changes.size();
                    publish(progress, new BatchMetadataEditProgress(processed, total));
                }
                checkCancelled(cancelled);
                history.markUndone(operationId);
                searchIndex.synchronizeAfterCommit(restoredIds);
                return new UndoExecution(restoredIds.size());
            });
            if (execution == null) throw new IllegalStateException("Batch metadata undo transaction returned no result");
            return new BatchMetadataEditResult(operationId, execution.restoredCount(), execution.restoredCount(), true);
        }
    }

    private BatchMetadataEditableSnapshot snapshot(Book book) {
        return new BatchMetadataEditableSnapshot(
                book.getTitle(),
                book.getSeries(),
                book.getSequenceNumber(),
                book.getLanguage() == null ? "und" : book.getLanguage().toString(),
                book.getYear(),
                nullToEmpty(book.getPublisher()),
                nullToEmpty(book.getKeywords()),
                nullToEmpty(book.getAnnotation()),
                book.getGenres().stream()
                        .map(genre -> new BatchMetadataGenreSnapshot(
                                genre.getId().asString(), genre.getName(),
                                genre.getParentId() == null ? null : genre.getParentId().asString(),
                                genre.getFb2Code()))
                        .toList());
    }

    private Book restoreEditable(Book current, BatchMetadataEditableSnapshot target, Map<String, Genre> genreCache) {
        List<Genre> resolvedGenres = resolveGenres(target.genres(), genreCache);
        BookMetadata old = current.getMetadata();
        BookMetadata metadata = BookMetadata.builder()
                .annotation(nullToEmpty(target.annotation()))
                .keywords(nullToEmpty(target.tags()))
                .language(LanguageCode.of(target.language() == null || target.language().isBlank() ? "und" : target.language()))
                .isbn(old.getIsbn())
                .review(old.getReview())
                .year(target.year())
                .publisher(nullToEmpty(target.publisher()))
                .libId(old.getLibId())
                .libraryRate(old.getLibraryRate())
                .translators(old.getTranslators())
                .city(old.getCity())
                .sourceUrl(old.getSourceUrl())
                .rate(old.getRate())
                .progress(old.getProgress())
                .build();
        return current.toBuilder()
                .title(target.title())
                .series(target.series())
                .sequenceNumber(target.sequenceNumber())
                .genres(resolvedGenres)
                .metadata(metadata)
                .updateDate(LocalDateTime.now())
                .build();
    }

    private List<Genre> resolveGenres(List<BatchMetadataGenreSnapshot> snapshots, Map<String, Genre> cache) {
        if (snapshots == null || snapshots.isEmpty()) return List.of();
        List<Genre> result = new ArrayList<>(snapshots.size());
        for (BatchMetadataGenreSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.code() == null || snapshot.code().isBlank()) continue;
            Genre genre = cache.computeIfAbsent(snapshot.code(), code -> genres.findById(GenreId.fromCode(code))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown genre code: " + code)));
            result.add(genre);
        }
        return List.copyOf(result);
    }

    private void requireCollection(String expected) {
        var current = collections.getCurrentCollection();
        if (expected == null || current == null || current.getId() == null || !expected.equals(current.getId())) {
            throw new IllegalStateException("Batch metadata operation belongs to a different collection");
        }
    }

    private static List<BookId> normalizeIds(List<BookId> source) {
        if (source == null || source.isEmpty()) return List.of();
        LinkedHashSet<BookId> unique = new LinkedHashSet<>();
        for (BookId id : source) {
            if (id == null) continue;
            unique.add(id);
            if (unique.size() > MAX_SELECTED_BOOKS) {
                throw new IllegalArgumentException("Batch metadata selection limit: " + MAX_SELECTED_BOOKS);
            }
        }
        return List.copyOf(unique);
    }

    private static Map<BookId, Book> orderedMap(List<Book> found) {
        if (found == null || found.isEmpty()) return Map.of();
        return found.stream().filter(Objects::nonNull).filter(book -> book.getId() != null)
                .collect(Collectors.toMap(Book::getId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
    }

    private static Book requireBook(Map<BookId, Book> current, BookId id) {
        Book book = current.get(id);
        if (book == null) throw new IllegalArgumentException("Book not found during batch operation: " + id);
        return book;
    }

    private static void checkCancelled(AtomicBoolean cancelled) {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Batch metadata operation cancelled");
        }
    }

    private static void publish(Consumer<BatchMetadataEditProgress> progress, BatchMetadataEditProgress value) {
        if (progress == null) return;
        try {
            progress.accept(value);
        } catch (RuntimeException ignored) {
            // Presentation callbacks are not allowed to break a collection transaction.
        }
    }

    private static String summarize(List<BatchMetadataEditRule> rules) {
        return rules.stream().map(rule -> rule.field() + ":" + rule.action()).collect(Collectors.joining(", "));
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record Execution(int changedCount, boolean searchScheduled) {
    }

    private record UndoExecution(int restoredCount) {
    }
}
