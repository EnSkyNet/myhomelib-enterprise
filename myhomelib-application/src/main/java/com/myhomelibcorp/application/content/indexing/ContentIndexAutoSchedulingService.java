package com.myhomelibcorp.application.content.indexing;

import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bridges committed catalogue changes into the restart-safe background full-text queue.
 * Exact, bounded change sets are indexed incrementally. Large/full-snapshot changes are
 * fail-closed as STALE instead of filling heap with hundreds of thousands of queue objects.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentIndexAutoSchedulingService {
    private static final int QUERY_BATCH_SIZE = 400;
    private static final int MAX_AUTO_CHANGED_BOOKS = 5_000;
    private static final int MAX_AUTO_TASKS = 10_000;
    private static final SupportedFormatRegistry FORMATS = SupportedFormatRegistry.standard();

    private final ContentIndexingQueueService queue;
    private final ContentIndexPort contentIndex;
    private final BookQueryRepository books;
    private final CollectionLifecyclePort collections;

    public SchedulingResult scheduleCurrentCollection(ImportChangeSet changes) {
        var current = collections.getCurrentCollection();
        if (current == null || current.getId() == null || current.getId().isBlank()) {
            return SchedulingResult.noCollection();
        }
        return schedule(current.getId(), changes);
    }

    public SchedulingResult schedule(String collectionId, ImportChangeSet changes) {
        String id = requiredCollection(collectionId);
        if (!activeCollectionIs(id)) {
            // This service resolves changed Book rows through the dynamic active collection repository.
            // Never read another collection and then write those artifacts into the requested index.
            return collectionChanged(id, changes == null ? 0
                    : changes.insertedCount() + changes.updatedCount() + changes.deletedCount());
        }
        queue.restore(id);
        if (changes == null) return SchedulingResult.noChanges();
        long changedCount = changes.insertedCount() + changes.updatedCount() + changes.deletedCount();
        if (changedCount <= 0) return SchedulingResult.noChanges();

        if (!changes.complete() || changedCount > MAX_AUTO_CHANGED_BOOKS) {
            queue.requireRebuild(id);
            return new SchedulingResult(false, true, 0, changedCount,
                    "full rebuild required for large/incomplete change set");
        }

        queue.holdStale(id);
        try {
            LinkedHashSet<BookId> changedIds = new LinkedHashSet<>();
            boolean invalidId = false;
            for (String value : changes.inserted()) invalidId |= !addId(changedIds, value);
            for (String value : changes.updated()) invalidId |= !addId(changedIds, value);
            LinkedHashSet<BookId> deletedIds = new LinkedHashSet<>();
            for (String value : changes.deleted()) invalidId |= !addId(deletedIds, value);
            if (invalidId) {
                log.warn("Content-index incremental scheduling rejected invalid changed book id(s); full rebuild required");
                queue.requireRebuild(id);
                return new SchedulingResult(false, true, 0, changedCount,
                        "full rebuild required because the change set contains invalid book ids");
            }

            LinkedHashSet<String> affected = new LinkedHashSet<>();
            changedIds.forEach(bookId -> affected.add(bookId.asString()));
            deletedIds.forEach(bookId -> affected.add(bookId.asString()));
            if (!queue.supersedeBooks(id, affected, 3_000L)) {
                queue.requireRebuild(id);
                return new SchedulingResult(false, true, 0, changedCount,
                        "full rebuild required because older content-index work did not quiesce");
            }

            // Removed rows must not remain searchable in an otherwise fresh content index.
            for (BookId deletedId : deletedIds) contentIndex.deleteBook(id, deletedId.asString());

            List<ContentIndexingTask> tasks = new ArrayList<>();
            List<BookId> idList = new ArrayList<>(changedIds);
            for (int from = 0; from < idList.size(); from += QUERY_BATCH_SIZE) {
                List<BookId> batch = idList.subList(from, Math.min(idList.size(), from + QUERY_BATCH_SIZE));
                // The repository is backed by the dynamic active DataSource. Check on both sides of
                // the read so a concurrent SWITCH can never make rows from collection B populate
                // derived-index tasks for collection A.
                if (!activeCollectionIs(id)) return collectionChanged(id, changedCount);
                Map<BookId, Book> current = books.findByIds(batch).stream()
                        .filter(book -> book != null && book.getId() != null)
                        .collect(Collectors.toMap(Book::getId, Function.identity(), (left, right) -> left));
                if (!activeCollectionIs(id)) return collectionChanged(id, changedCount);
                for (BookId bookId : batch) {
                    Book book = current.get(bookId);
                    // Clear old content first. New task(s), if any, repopulate the current artifact set.
                    contentIndex.deleteBook(id, bookId.asString());
                    if (book == null || book.isDeleted()) continue;
                    collectTasks(id, book, tasks);
                    if (tasks.size() > MAX_AUTO_TASKS) {
                        queue.requireRebuild(id);
                        return new SchedulingResult(false, true, 0, changedCount,
                                "full rebuild required because incremental task set is too large");
                    }
                }
            }

            if (tasks.isEmpty()) {
                return new SchedulingResult(true, false, 0, changedCount, "");
            }
            // Do not resurrect work for a collection that became inactive after its rows were read.
            // The old index remains explicitly stale and will be rebuilt on the next activation.
            if (!activeCollectionIs(id)) return collectionChanged(id, changedCount);
            if (tasks.size() > queue.availableCapacity()) {
                queue.requireRebuild(id);
                return new SchedulingResult(false, true, 0, changedCount,
                        "background content-index queue is at capacity");
            }
            try {
                int accepted = queue.enqueueAll(tasks);
                return new SchedulingResult(true, false, accepted, changedCount, "");
            } catch (RuntimeException enqueueFailure) {
                log.warn("Incremental content-index queue rejected committed changes; full rebuild required", enqueueFailure);
                queue.requireRebuild(id);
                return new SchedulingResult(false, true, 0, changedCount,
                        "full rebuild required because the background queue could not accept the complete batch");
            }
        } finally {
            queue.releaseStaleHold(id);
        }
    }

    public void markStale(String collectionId) {
        queue.requireRebuild(requiredCollection(collectionId));
    }

    /** Use for committed content/storage mutations that do not expose an exact ImportChangeSet. */
    public void markCurrentCollectionStale() {
        var current = collections.getCurrentCollection();
        if (current != null && current.getId() != null && !current.getId().isBlank()) {
            queue.requireRebuild(current.getId());
        }
    }

    private boolean activeCollectionIs(String collectionId) {
        var active = collections.getCurrentCollection();
        String activeId = active == null || active.getId() == null ? "" : active.getId().trim();
        return collectionId.equals(activeId);
    }

    private SchedulingResult collectionChanged(String collectionId, long changedCount) {
        queue.requireRebuild(collectionId);
        return new SchedulingResult(false, true, 0, changedCount,
                "full rebuild required because the active collection changed during incremental scheduling");
    }

    private static void collectTasks(String collectionId, Book book, List<ContentIndexingTask> out) {
        List<BookArtifact> artifacts = book.getArtifacts();
        if (artifacts != null && !artifacts.isEmpty()) {
            for (BookArtifact artifact : artifacts) {
                if (artifact == null || !artifact.isAvailable()) continue;
                String format = artifact.getFormat();
                if (!fullText(format)) continue;
                String sourcePath = artifact.getFile() == null ? "" : artifact.getFile().getFullPath();
                if (sourcePath == null || sourcePath.isBlank()) continue;
                out.add(task(collectionId, book.getId().asString(), artifact.getId(), sourcePath, format));
            }
            return;
        }
        if (!book.isLocal()) return;
        String source = book.getArchiveEntry() == null || book.getArchiveEntry().isBlank()
                ? book.getFileName() : book.getArchiveEntry();
        String format = FORMATS.detect(source).map(f -> f.id()).orElse("");
        if (!fullText(format)) return;
        String sourcePath = book.getFile() == null ? "" : book.getFile().getFullPath();
        if (sourcePath == null || sourcePath.isBlank()) return;
        out.add(task(collectionId, book.getId().asString(), book.getId().asString() + ":legacy", sourcePath, format));
    }

    private static ContentIndexingTask task(String collectionId, String bookId, String artifactId,
                                             String sourcePath, String format) {
        return new ContentIndexingTask(UUID.randomUUID().toString(), collectionId, bookId, artifactId,
                sourcePath, format, ContentIndexingPriority.NORMAL, Instant.now());
    }

    private static boolean fullText(String format) {
        return format != null && !format.isBlank()
                && FORMATS.byId(format).map(f -> f.fullTextSupported()).orElse(false);
    }

    private static boolean addId(Set<BookId> target, String raw) {
        if (raw == null || raw.isBlank()) return false;
        try {
            target.add(BookId.fromString(raw.trim()));
            return true;
        } catch (RuntimeException invalid) {
            log.warn("Invalid changed book id in content-index scheduling: {}", raw);
            return false;
        }
    }

    private static String requiredCollection(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("collectionId is required");
        return value.trim();
    }

    public record SchedulingResult(boolean scheduled, boolean rebuildRequired, int queuedTasks,
                                   long changedBooks, String detail) {
        public SchedulingResult {
            detail = detail == null ? "" : detail;
        }
        static SchedulingResult noCollection() { return new SchedulingResult(false, false, 0, 0, "no active collection"); }
        static SchedulingResult noChanges() { return new SchedulingResult(true, false, 0, 0, ""); }
    }
}
