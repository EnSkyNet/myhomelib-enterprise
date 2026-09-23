package com.myhomelibcorp.application.usecase.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Removes downloaded bytes while preserving the online catalog entry and user state. */
@Component
@Slf4j
public class RemoveLocalBookCopyUseCase {
    private final BookResourcePort resources;
    private final BookQueryRepository queries;
    private final BookCommandRepository commands;
    private final CatalogUpdateTrackingPort catalogUpdateTrackingPort;
    private final StatisticsRepository statisticsRepository;
    private final CommittedCatalogMutationService committedCatalogMutationService;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Production constructor — Spring uses this one. */
    @Autowired
    public RemoveLocalBookCopyUseCase(BookResourcePort resources, BookQueryRepository queries,
                                      BookCommandRepository commands, CatalogUpdateTrackingPort catalogUpdateTrackingPort,
                                      StatisticsRepository statisticsRepository,
                                      CommittedCatalogMutationService committedCatalogMutationService,
                                      CollectionLifecyclePort collectionLifecyclePort,
                                      LibraryOperationCoordinator operationCoordinator) {
        this.resources = resources;
        this.queries = queries;
        this.commands = commands;
        this.catalogUpdateTrackingPort = catalogUpdateTrackingPort;
        this.statisticsRepository = statisticsRepository;
        this.committedCatalogMutationService = committedCatalogMutationService;
        this.collectionLifecyclePort = collectionLifecyclePort;
        this.operationCoordinator = operationCoordinator;
    }

    /** Compatibility constructor for focused tests that do not share an application coordinator. */
    public RemoveLocalBookCopyUseCase(BookResourcePort resources, BookQueryRepository queries,
                                      BookCommandRepository commands, CatalogUpdateTrackingPort catalogUpdateTrackingPort,
                                      StatisticsRepository statisticsRepository,
                                      CommittedCatalogMutationService committedCatalogMutationService,
                                      CollectionLifecyclePort collectionLifecyclePort) {
        this(resources, queries, commands, catalogUpdateTrackingPort, statisticsRepository,
                committedCatalogMutationService, collectionLifecyclePort, new LibraryOperationCoordinator());
    }

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
        return execute(book, activeCollectionId());
    }

    public int execute(BookDto book, String expectedCollectionId) throws Exception {
        var lease = operationCoordinator.acquireDetached(LibraryOperationType.EDIT);
        try {
            assertExpectedCollection(expectedCollectionId);
            BookDto latest = reloadLatestStorage(book);
            int result = executeLocked(latest, expectedCollectionId);
            lease.markCompleted("");
            return result;
        } catch (Exception failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private int executeLocked(BookDto book, String expectedCollectionId) throws Exception {
        validateBook(book);
        assertExpectedCollection(expectedCollectionId);

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
            assertExpectedCollection(expectedCollectionId);
            staged = resources.stagePhysicalFileForDeletion(physical, managedRoot, expectedCollectionId, affectedIds);
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

        committedCatalogMutationService.synchronizeContentAfterCommit(affectedIds);

        // At this point SQLite is authoritative and already committed. Failure to clean a
        // recovery hard-link/copy must not turn a successful catalog mutation into a false
        // user-visible failure; keeping the recovery bytes is conservative and data-safe.
        commitStagedSafely(staged);
        invalidateStatisticsSafely();
        return archived && !sharedRows.isEmpty() ? sharedRows.size() : 1;
    }

    /**
     * Removes multiple local copies as one catalog transaction while staging each physical file only once.
     * If any staging or database step fails, every previously staged file is rolled back before the error escapes.
     */
    public int executeBatch(List<BookDto> books) throws Exception {
        if (books == null || books.isEmpty()) return 0;
        return executeBatch(books, activeCollectionId());
    }

    public int executeBatch(List<BookDto> books, String expectedCollectionId) throws Exception {
        if (books == null || books.isEmpty()) return 0;
        var lease = operationCoordinator.acquireDetached(LibraryOperationType.EDIT);
        try {
            assertExpectedCollection(expectedCollectionId);
            int result = executeBatchLocked(books, expectedCollectionId);
            lease.markCompleted("");
            return result;
        } catch (Exception failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private int executeBatchLocked(List<BookDto> books, String expectedCollectionId) throws Exception {
        LinkedHashMap<String, BatchPlan> uniquePlans = new LinkedHashMap<>();
        for (BookDto supplied : books) {
            validateBook(supplied);
            BookDto book = reloadLatestStorage(supplied);
            assertExpectedCollection(expectedCollectionId);
            BatchPlan plan = prepareBatchPlan(book);
            String key = plan.archived() && plan.physical() != null
                    ? "archive:" + plan.physical().toAbsolutePath().normalize()
                    : "book:" + plan.requestedBookId().asString();
            uniquePlans.putIfAbsent(key, plan);
        }
        if (uniquePlans.isEmpty()) return 0;

        LinkedHashSet<BookId> allAffected = new LinkedHashSet<>();
        LinkedHashMap<Path, LinkedHashSet<BookId>> affectedByPhysical = new LinkedHashMap<>();
        LinkedHashMap<Path, BookDto> ownerByPhysical = new LinkedHashMap<>();
        for (BatchPlan plan : uniquePlans.values()) {
            allAffected.addAll(plan.affectedIds());
            if (plan.physical() != null) {
                Path physical = plan.physical().toAbsolutePath().normalize();
                affectedByPhysical.computeIfAbsent(physical, ignored -> new LinkedHashSet<>()).addAll(plan.affectedIds());
                ownerByPhysical.putIfAbsent(physical, plan.book());
            }
        }

        List<BookResourcePort.StagedDeletion> staged = new ArrayList<>();
        try {
            for (Map.Entry<Path, LinkedHashSet<BookId>> entry : affectedByPhysical.entrySet()) {
                List<BookId> ids = List.copyOf(entry.getValue());
                assertManagedDownloadProvenance(ids, entry.getKey());
                BookDto owner = ownerByPhysical.get(entry.getKey());
                assertExpectedCollection(expectedCollectionId);
                staged.add(resources.stagePhysicalFileForDeletion(
                        entry.getKey(), managedRoot(owner), expectedCollectionId, ids));
            }
        } catch (Exception stagingFailure) {
            rollbackAll(staged, stagingFailure);
            throw stagingFailure;
        }

        try {
            committedCatalogMutationService.executeSynchronized(List.copyOf(allAffected), () -> {
                LinkedHashSet<BookId> mutated = new LinkedHashSet<>();
                for (BatchPlan plan : uniquePlans.values()) {
                    if (plan.archived() && !plan.sharedRows().isEmpty()) {
                        for (Book row : plan.sharedRows()) {
                            if (row == null || row.getId() == null || !mutated.add(row.getId())) continue;
                            commands.updateStorage(row.getId(), row.getCollectionRoot(), row.getFolder(),
                                    row.getFileName(), row.getArchiveEntry(), false);
                            catalogUpdateTrackingPort.clearDownloadedBaseline(row.getId());
                        }
                    } else if (mutated.add(plan.requestedBookId())) {
                        BookDto book = plan.book();
                        commands.updateStorage(plan.requestedBookId(),
                                book.getCollectionRoot(), book.getFolder(), book.getFileName(), book.getArchiveEntry(), false);
                        catalogUpdateTrackingPort.clearDownloadedBaseline(plan.requestedBookId());
                    }
                }
            });
        } catch (RuntimeException | Error databaseFailure) {
            rollbackAll(staged, databaseFailure);
            throw databaseFailure;
        }

        committedCatalogMutationService.synchronizeContentAfterCommit(List.copyOf(allAffected));
        for (BookResourcePort.StagedDeletion item : staged) commitStagedSafely(item);
        invalidateStatisticsSafely();
        return allAffected.size();
    }

    private BatchPlan prepareBatchPlan(BookDto book) {
        BookId requestedBookId = BookId.fromString(book.getId());
        Path physical = resources.locateBookFile(
                book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry()).orElse(null);
        boolean archived = isArchived(book);
        List<Book> sharedRows = archived
                ? queries.findByArchiveContainer(book.getCollectionRoot(), book.getFolder(), physical == null ? "" : physical.toString())
                : List.of();
        return new BatchPlan(book, requestedBookId, physical, archived, sharedRows,
                affectedIds(requestedBookId, sharedRows));
    }

    private static void rollbackAll(List<BookResourcePort.StagedDeletion> staged, Throwable originalFailure) {
        for (int i = staged.size() - 1; i >= 0; i--) {
            rollbackStaged(staged.get(i), originalFailure);
        }
    }

    private record BatchPlan(BookDto book, BookId requestedBookId, Path physical, boolean archived,
                             List<Book> sharedRows, List<BookId> affectedIds) { }

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

    private BookDto reloadLatestStorage(BookDto supplied) {
        validateBook(supplied);
        BookId id = BookId.fromString(supplied.getId());
        Book latest = queries.findById(id)
                .orElseThrow(() -> new IllegalStateException("Книгу не знайдено в активній колекції: " + supplied.getId()));
        BookDto refreshed = supplied.toBuilder().build();
        refreshed.setCollectionRoot(latest.getCollectionRoot());
        refreshed.setFolder(latest.getFolder());
        refreshed.setFileName(latest.getFileName());
        refreshed.setArchiveEntry(latest.getArchiveEntry());
        refreshed.setFileSize(latest.getFileSize());
        refreshed.setLocal(latest.isLocal());
        refreshed.setMissingSince(latest.getMissingSince());
        return refreshed;
    }

    private void assertExpectedCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            throw new SecurityException("Physical deletion is blocked because the expected collection id is missing");
        }
        var current = collectionLifecyclePort.getCurrentCollection();
        if (current == null || current.getId() == null || !expectedCollectionId.equals(current.getId())) {
            throw new IllegalStateException("Активна колекція змінилася до видалення локальної копії");
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