package com.myhomelibcorp.application.usecase.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

/** Removes downloaded bytes while preserving the online catalog entry and user state. */
@Component
@RequiredArgsConstructor
@Slf4j
public class RemoveLocalBookCopyUseCase {
    private final BookResourcePort resources;
    private final BookQueryRepository queries;
    private final BookCommandRepository commands;
    private final CatalogUpdateTrackingPort catalogUpdateTrackingPort;
    private final StatisticsRepository statisticsRepository;
    private final CommittedCatalogMutationService committedCatalogMutationService;
    private final CollectionLifecyclePort collectionLifecyclePort;

    /** Read-only removal impact used by UI confirmation. */
    public RemovalPreview preview(BookDto book) {
        validateBook(book);
        Path physical = resources.locateBookFile(
                book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry()).orElse(null);
        boolean archived = isArchived(book);
        int affectedBooks = 1;
        if (archived) {
            List<Book> affected = queries.findByArchiveContainer(
                    book.getCollectionRoot(), book.getFolder(), physical == null ? "" : physical.toString());
            if (!affected.isEmpty()) affectedBooks = affected.size();
        }
        return new RemovalPreview(physical, affectedBooks, archived && affectedBooks > 1);
    }

    public record RemovalPreview(Path physicalPath, int affectedBooks, boolean sharedArchive) { }

    /**
     * Crash-safe two-phase local-copy removal.
     * <ol>
     *     <li>Physical bytes are moved out of the visible path but preserved by a reversible
     *         recovery link/copy inside the same managed root.</li>
     *     <li>All affected catalog rows and download baselines are mutated in one SQLite transaction;
     *         Lucene synchronization is scheduled only after that transaction commits.</li>
     *     <li>Only after a successful DB commit is the recovery copy released.</li>
     * </ol>
     * If the DB phase fails, the original physical path is restored before the error escapes.
     *
     * @return number of catalog rows switched to non-local. For a shared archive this
     *         includes every book whose physical bytes live in the deleted archive.
     */
    public int execute(BookDto book) throws Exception {
        validateBook(book);

        BookId requestedBookId = BookId.fromString(book.getId());
        Path physical = resources.locateBookFile(
                book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry()).orElse(null);
        boolean archived = isArchived(book);

        List<Book> sharedRows = archived
                ? queries.findByArchiveContainer(book.getCollectionRoot(), book.getFolder(), physical == null ? "" : physical.toString())
                : List.of();
        List<BookId> affectedIds = affectedIds(requestedBookId, sharedRows);

        BookResourcePort.StagedDeletion staged = null;
        if (physical != null) {
            assertManagedDownloadProvenance(affectedIds, physical);
            Path managedRoot = managedRoot(book);
            String collectionId = activeCollectionId();
            staged = resources.stagePhysicalFileForDeletion(physical, managedRoot, collectionId, affectedIds);
        }

        try {
            committedCatalogMutationService.executeSynchronized(affectedIds, () -> {
                if (archived && !sharedRows.isEmpty()) {
                    for (Book row : sharedRows) {
                        commands.updateStorage(row.getId(), row.getCollectionRoot(), row.getFolder(),
                                row.getFileName(), row.getArchiveEntry(), false);
                        catalogUpdateTrackingPort.clearDownloadedBaseline(row.getId());
                    }
                } else {
                    commands.updateStorage(requestedBookId,
                            book.getCollectionRoot(), book.getFolder(), book.getFileName(), book.getArchiveEntry(), false);
                    catalogUpdateTrackingPort.clearDownloadedBaseline(requestedBookId);
                }
            });
        } catch (RuntimeException | Error databaseFailure) {
            rollbackStaged(staged, databaseFailure);
            throw databaseFailure;
        }

        // At this point SQLite is authoritative and already committed. Failure to clean a
        // recovery hard-link/copy must not turn a successful catalog mutation into a false
        // user-visible failure; keeping the recovery bytes is conservative and data-safe.
        commitStagedSafely(staged);
        invalidateStatisticsSafely();
        return archived && !sharedRows.isEmpty() ? sharedRows.size() : 1;
    }

    private static void validateBook(BookDto book) {
        if (book == null || book.getId() == null || book.getId().isBlank()) {
            throw new IllegalArgumentException("Book is required");
        }
    }

    private static boolean isArchived(BookDto book) {
        return book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank();
    }

    private static List<BookId> affectedIds(BookId requestedBookId, List<Book> sharedRows) {
        LinkedHashSet<BookId> ids = new LinkedHashSet<>();
        if (sharedRows != null) {
            for (Book row : sharedRows) if (row != null && row.getId() != null) ids.add(row.getId());
        }
        if (ids.isEmpty()) ids.add(requestedBookId);
        return List.copyOf(ids);
    }

    private void assertManagedDownloadProvenance(List<BookId> affectedIds, Path physical) {
        boolean managedDownload = affectedIds.stream().anyMatch(catalogUpdateTrackingPort::hasDownloadedBaseline);
        if (!managedDownload) {
            throw new SecurityException("Physical deletion is blocked because the file has no managed-download provenance: " + physical);
        }
    }

    private String activeCollectionId() {
        var collection = collectionLifecyclePort.getCurrentCollection();
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) {
            throw new SecurityException("Physical deletion is blocked because there is no stable active collection id");
        }
        return collection.getId();
    }

    private static Path managedRoot(BookDto book) {
        String root = book.getCollectionRoot();
        if (root == null || root.isBlank()) {
            throw new SecurityException("Physical deletion is blocked because the collection has no managed root");
        }
        try {
            return Path.of(root);
        } catch (RuntimeException invalidPath) {
            throw new SecurityException("Physical deletion is blocked because the managed root is invalid", invalidPath);
        }
    }

    private static void rollbackStaged(BookResourcePort.StagedDeletion staged, Throwable originalFailure) {
        if (staged == null) return;
        try {
            staged.rollback();
        } catch (IOException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
        }
    }

    private void commitStagedSafely(BookResourcePort.StagedDeletion staged) {
        if (staged == null) return;
        try {
            staged.commit();
        } catch (IOException cleanupFailure) {
            log.error("Catalog commit completed, but local-copy recovery bytes could not be released: {}",
                    staged.recoveryPath(), cleanupFailure);
        }
    }

    private void invalidateStatisticsSafely() {
        try {
            statisticsRepository.invalidate();
        } catch (RuntimeException error) {
            log.warn("Не вдалося інвалідувати кеш статистики після видалення локальної копії", error);
        }
    }
}
