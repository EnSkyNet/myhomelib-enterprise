package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.shared.util.ThrowableMessages;
import com.myhomelibcorp.application.catalog.CatalogSourceIdentity;
import com.myhomelibcorp.application.catalog.CatalogSourceProfile;
import com.myhomelibcorp.application.catalog.CatalogSourceState;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.imports.diagnostics.ImportIssue;
import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.port.out.catalog.CatalogSourceStatePort;
import com.myhomelibcorp.application.port.out.backup.CollectionBackupPort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogPackage;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogUpdatePlan;
import com.myhomelibcorp.application.port.out.download.RemoteDownloadProgress;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.StatisticsRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.CatalogUpdateRecoveryFiles;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;

import java.nio.file.Files;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleConsumer;
import java.util.function.Consumer;
import java.util.UUID;

/** Reliable state machine for updating the active collection from a remote catalog source. */
public class UpdateCollectionFromNetworkUseCase {
    private final RemoteCatalogDownloadPort downloader;
    private final ImportFileUseCase importer;
    private final CollectionLifecycleService lifecycle;
    private final CatalogSourceStatePort sourceState;
    private final SearchIndexer searchIndexer;
    private final BookQueryRepository bookQueryRepository;
    private final StatisticsRepository statisticsRepository;
    private final CollectionBackupPort collectionBackupPort;
    private final int changeTrackingLimit;
    private final LibraryOperationCoordinator operationCoordinator;

    public UpdateCollectionFromNetworkUseCase(
            RemoteCatalogDownloadPort downloader,
            ImportFileUseCase importer,
            CollectionLifecycleService lifecycle,
            CatalogSourceStatePort sourceState,
            SearchIndexer searchIndexer,
            BookQueryRepository bookQueryRepository) {
        this(downloader, importer, lifecycle, sourceState, searchIndexer, bookQueryRepository, null, null,
                ImportChangeAccumulator.DEFAULT_TRACKED_ID_LIMIT, new LibraryOperationCoordinator());
    }

    public UpdateCollectionFromNetworkUseCase(
            RemoteCatalogDownloadPort downloader,
            ImportFileUseCase importer,
            CollectionLifecycleService lifecycle,
            CatalogSourceStatePort sourceState,
            SearchIndexer searchIndexer,
            BookQueryRepository bookQueryRepository,
            int changeTrackingLimit) {
        this(downloader, importer, lifecycle, sourceState, searchIndexer, bookQueryRepository, null, null,
                changeTrackingLimit, new LibraryOperationCoordinator());
    }

    public UpdateCollectionFromNetworkUseCase(
            RemoteCatalogDownloadPort downloader,
            ImportFileUseCase importer,
            CollectionLifecycleService lifecycle,
            CatalogSourceStatePort sourceState,
            SearchIndexer searchIndexer,
            BookQueryRepository bookQueryRepository,
            int changeTrackingLimit,
            LibraryOperationCoordinator operationCoordinator) {
        this(downloader, importer, lifecycle, sourceState, searchIndexer, bookQueryRepository, null, null,
                changeTrackingLimit, operationCoordinator);
    }

    public UpdateCollectionFromNetworkUseCase(
            RemoteCatalogDownloadPort downloader,
            ImportFileUseCase importer,
            CollectionLifecycleService lifecycle,
            CatalogSourceStatePort sourceState,
            SearchIndexer searchIndexer,
            BookQueryRepository bookQueryRepository,
            StatisticsRepository statisticsRepository,
            CollectionBackupPort collectionBackupPort,
            int changeTrackingLimit,
            LibraryOperationCoordinator operationCoordinator) {
        this.downloader = downloader;
        this.importer = importer;
        this.lifecycle = lifecycle;
        this.sourceState = sourceState;
        this.searchIndexer = searchIndexer;
        this.bookQueryRepository = bookQueryRepository;
        this.statisticsRepository = statisticsRepository;
        this.collectionBackupPort = collectionBackupPort;
        this.changeTrackingLimit = ImportChangeAccumulator.normalizeLimit(changeTrackingLimit);
        this.operationCoordinator = java.util.Objects.requireNonNull(operationCoordinator, "operationCoordinator");
    }

    public ImportResult execute(Collection collection, String source, AtomicBoolean cancel, DoubleConsumer progress) {
        return execute(collection, source, cancel, progress, null);
    }

    public ImportResult execute(Collection collection, String source, AtomicBoolean cancel, DoubleConsumer progress,
                                Consumer<OperationProgress> operationProgress) {
        try (var ignored = operationCoordinator.acquire(LibraryOperationType.UPDATE)) {
            return executeLocked(collection, source, cancel, progress, operationProgress);
        }
    }

    private ImportResult executeLocked(Collection collection, String source, AtomicBoolean cancel, DoubleConsumer progress,
                                       Consumer<OperationProgress> operationProgress) {
        if (collection == null) throw new IllegalArgumentException("Колекцію не вибрано");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Не задано URL INPX/сервера");
        Collection active = lifecycle.getCurrentCollection();
        if (active == null || active.getId() == null || collection.getId() == null || !active.getId().equals(collection.getId())) {
            throw new IllegalStateException("Оновлювати з мережі можна лише активну колекцію");
        }

        AtomicBoolean flag = cancel == null ? new AtomicBoolean(false) : cancel;
        DoubleConsumer sink = progress == null ? p -> { } : p -> {
            try { progress.accept(p); } catch (RuntimeException ignored) { }
        };
        Consumer<OperationProgress> telemetry = operationProgress == null ? p -> { } : operationProgress;
        String operationId = "catalog-update-" + UUID.randomUUID();
        long operationStartedNanos = System.nanoTime();
        String sourceKey = CatalogSourceIdentity.remoteCollection(collection.getId());
        CatalogSourceState durable = sourceState.get(sourceKey);
        String localVersion = durable.appliedVersion();
        String safeSource = safeDisplaySource(source.trim());
        AtomicReference<OperationStage> currentStage = new AtomicReference<>(OperationStage.CHECKING_SERVER);
        List<Path> downloaded = new ArrayList<>();
        Path checkpoint = null;
        boolean mutationMayHaveCommitted = false;
        boolean keepCheckpoint = false;

        long imported = 0, skipped = 0, duplicates = 0, errors = 0;
        long withoutAuthor = 0, withoutGenre = 0, explicitlyDeleted = 0;
        ImportChangeAccumulator changes = new ImportChangeAccumulator(changeTrackingLimit);
        List<ImportIssue> issues = new ArrayList<>();

        try {
            recoverPendingUpdateBeforeNewAttempt(active, telemetry, operationId);

            currentStage.set(OperationStage.CHECKING_SERVER);
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.CHECKING_SERVER, true)
                    .withCurrentItem(safeSource));
            sink.accept(0.01);

            currentStage.set(OperationStage.DOWNLOADING);
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.DOWNLOADING, true)
                    .withCurrentItem(safeSource));
            RemoteCatalogUpdatePlan plan = downloader.downloadUpdates(
                    active, source.trim(), localVersion, flag,
                    p -> sink.accept(Math.min(0.35, 0.03 + p * 0.32)),
                    p -> emitDownloadProgress(telemetry, operationId, p));
            if (plan == null) throw new IllegalStateException("Сервер не повернув план оновлення каталогу");
            sourceState.recordChecked(sourceKey, safeSource, profileType(source), plan.latestVersion());
            checkCancelled(flag);
            if (plan.upToDate()) {
                sink.accept(1.0);
                emit(telemetry, OperationProgress.stage(operationId, OperationStage.COMPLETED, false)
                        .withCounts(0, 0, 0, 0, 0, 0, 0));
                return new ImportResult(0, 0, 0, 0, elapsedMillis(operationStartedNanos),
                        ImportStatus.SUCCESS, ImportChangeSet.empty(true), List.of());
            }

            currentStage.set(OperationStage.VALIDATING);
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.VALIDATING, true)
                    .withProgress(plan.packages().size(), plan.packages().size()));

            RemoteCatalogPackage unchangedFullSnapshot = unchangedSingleFullSnapshot(plan, sourceKey);
            if (unchangedFullSnapshot != null) {
                checkCancelled(flag);
                downloaded.add(unchangedFullSnapshot.file());
                sourceState.recordDownloaded(sourceKey, unchangedFullSnapshot.metadata().etag(),
                        unchangedFullSnapshot.metadata().lastModified(), unchangedFullSnapshot.metadata().sha256(),
                        unchangedFullSnapshot.metadata().datasetSchema());
                currentStage.set(OperationStage.FINALIZING);
                emit(telemetry, OperationProgress.stage(operationId, OperationStage.FINALIZING, false)
                        .withCounts(0, 0, 0, 0, 0, 0, 0));
                sourceState.recordApplied(sourceKey, lastVersion(plan));
                sink.accept(1.0);
                emit(telemetry, OperationProgress.stage(operationId, OperationStage.COMPLETED, false)
                        .withCounts(0, 0, 0, 0, 0, 0, 0));
                return new ImportResult(0, 0, 0, 0, elapsedMillis(operationStartedNanos),
                        ImportStatus.SUCCESS, ImportChangeSet.empty(true), List.of());
            }

            // Each catalog package is committed in bounded DB transactions. A checkpoint is therefore
            // created immediately before the first possible catalog mutation, not before network I/O.
            // This avoids a giant long-lived SQLite transaction while still giving late Lucene/statistics
            // failures and cancellation a deterministic way back to the previous catalog state.
            if (!plan.packages().isEmpty() && collectionBackupPort != null) {
                currentStage.set(OperationStage.CREATING_CHECKPOINT);
                checkpoint = updateCheckpointPath(active, operationId);
                emit(telemetry, OperationProgress.stage(operationId, OperationStage.CREATING_CHECKPOINT, false)
                        .withCurrentItem("SQLite checkpoint"));
                collectionBackupPort.createDatabaseSnapshot(active, checkpoint);
                collectionBackupPort.validateDatabaseFile(checkpoint);
                // The durable marker is the crash boundary: from this point a JVM/OS termination
                // will cause the next collection open to restore this validated pre-update DB.
                CatalogUpdateRecoveryFiles.markPending(active.getId(), operationId);
            }

            for (int packageIndex = 0; packageIndex < plan.packages().size(); packageIndex++) {
                RemoteCatalogPackage pkg = plan.packages().get(packageIndex);
                if (pkg == null || pkg.file() == null) throw new IllegalStateException("Порожній пакет оновлення");
                downloaded.add(pkg.file());
                checkCancelled(flag);
                sourceState.recordDownloaded(sourceKey, pkg.metadata().etag(), pkg.metadata().lastModified(),
                        pkg.metadata().sha256(), pkg.metadata().datasetSchema());

                String packageLabel = (pkg.fullSnapshot() ? "FULL · " : "DELTA · ") + pkg.file().getFileName();
                currentStage.set(OperationStage.READING_CATALOG);
                emit(telemetry, OperationProgress.stage(operationId, OperationStage.READING_CATALOG, true)
                        .withProgress(packageIndex, plan.packages().size())
                        .withCurrentItem(packageLabel)
                        .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                                skipped, duplicates, issues.size(), errors));

                Path root = onlineBookStorageRoot(active);
                double importStart = 0.35 + 0.45 * packageIndex / plan.packages().size();
                double importSpan = 0.45 / plan.packages().size();
                // Bounded import batches may commit before execute() returns. From this point onward any
                // failure/cancellation must restore the pre-update checkpoint when one is available.
                mutationMayHaveCommitted = true;
                ImportResult current = importer.execute(ImportContext.builder()
                        .file(pkg.file())
                        .rootDirectory(root)
                        .updateExisting(true)
                        .indexAfterSave(false)
                        .publishFinishedEvent(false)
                        .catalogFullSnapshot(pkg.fullSnapshot())
                        .catalogSourceKey(sourceKey)
                        .catalogSourceLocation(pkg.sourceUrl())
                        .batchSize(1000)
                        .cancelFlag(flag)
                        .operationId(operationId)
                        .operationProgressListener(p -> {
                            if (p != null) currentStage.set(p.stage());
                            emit(telemetry, p == null ? null : p.withCurrentItem(packageLabel));
                        })
                        .statusConsumer(ignored -> { })
                        .progressListener(p -> sink.accept(Math.min(0.80, importStart + p * importSpan)))
                        .build());

                if (flag.get() || current.status() == ImportStatus.CANCELLED) throw new UpdateCancelledException();
                if (isFailure(current.status())) {
                    throw new IllegalStateException("Імпорт пакета завершився зі статусом " + current.status());
                }
                imported += current.imported();
                skipped += current.skipped();
                duplicates += current.duplicates();
                errors += current.errors();
                withoutAuthor += current.withoutAuthor();
                withoutGenre += current.withoutGenre();
                explicitlyDeleted += current.explicitlyDeleted();
                changes.merge(current.changes());
                appendIssuesBounded(issues, current.issues());
            }

            currentStage.set(OperationStage.APPLYING_DELETIONS);
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.APPLYING_DELETIONS, true)
                    .withProgress(changes.deletedCount(), changes.deletedCount())
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors));

            final ImportChangeSet indexChanges = changes.snapshot();
            final long indexSkipped = skipped;
            final long indexDuplicates = duplicates;
            final long indexErrors = errors;
            final long indexWarnings = issues.size();

            boolean catalogMutated = imported > 0
                    || indexChanges.insertedCount() > 0
                    || indexChanges.updatedCount() > 0
                    || indexChanges.deletedCount() > 0;

            sink.accept(0.82);
            if (catalogMutated) {
                currentStage.set(OperationStage.UPDATING_SEARCH_INDEX);
                if (!indexChanges.complete()) {
                    searchIndexer.rebuildIndex(flag, p -> {
                        checkCancelled(flag);
                        emit(telemetry, OperationProgress.stage(operationId, OperationStage.UPDATING_SEARCH_INDEX, true)
                                .withProgress(p.processed(), p.total())
                                .withCounts(indexChanges.insertedCount(), indexChanges.updatedCount(), indexChanges.deletedCount(),
                                        indexSkipped, indexDuplicates, indexWarnings, indexErrors));
                    });
                } else {
                    applyIncrementalIndex(indexChanges, flag, p -> emit(telemetry,
                            OperationProgress.stage(operationId, OperationStage.UPDATING_SEARCH_INDEX, true)
                                    .withProgress(p[0], p[1])
                                    .withCounts(indexChanges.insertedCount(), indexChanges.updatedCount(), indexChanges.deletedCount(),
                                            indexSkipped, indexDuplicates, indexWarnings, indexErrors)));
                }
                sink.accept(0.93);
                checkCancelled(flag);

                if (statisticsRepository != null) {
                    currentStage.set(OperationStage.REFRESHING_STATISTICS);
                    emit(telemetry, OperationProgress.stage(operationId, OperationStage.REFRESHING_STATISTICS, false)
                            .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                                    skipped, duplicates, issues.size(), errors));
                    statisticsRepository.invalidate();
                    statisticsRepository.refreshStatistics();
                }
            }
            sink.accept(0.98);

            currentStage.set(OperationStage.FINALIZING);
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.FINALIZING, false)
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors));
            String applied = lastVersion(plan);
            sourceState.recordApplied(sourceKey, applied);

            // Removing the durable pending marker is the catalog-update commit point. Do not report
            // success while the marker still exists: a later restart would otherwise interpret the
            // successful update as interrupted and restore the pre-update checkpoint.
            if (checkpoint != null) {
                commitRecoveryCheckpoint(active, checkpoint);
                checkpoint = null;
            }

            sink.accept(1.0);
            ImportStatus status = errors > 0 || !issues.isEmpty() ? ImportStatus.SUCCESS_WITH_WARNINGS : ImportStatus.SUCCESS;
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.COMPLETED, false)
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors));
            return new ImportResult(imported, skipped, duplicates, errors, elapsedMillis(operationStartedNanos),
                    status, indexChanges, issues, withoutAuthor, withoutGenre, explicitlyDeleted);
        } catch (UpdateCancelledException e) {
            OperationStage failedAt = currentStage.get();
            RollbackOutcome rollback = attemptRollback(active, checkpoint, mutationMayHaveCommitted, telemetry,
                    operationId, changes, skipped, duplicates, issues.size(), errors, e);
            keepCheckpoint = rollback.attempted() && !rollback.succeeded();
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.CANCELLED, false)
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors));
            safeFailure(sourceKey, failureDescription(failedAt, "Оновлення скасовано", rollback));
            String message = rollback.attempted() && !rollback.succeeded()
                    ? "Оновлення скасовано; автоматичний відкат не завершено" : "Оновлення скасовано";
            throw new CatalogUpdateFailureException(message, e, failedAt, safeSource, localVersion,
                    mutationMayHaveCommitted, rollback.attempted(), rollback.succeeded());
        } catch (RuntimeException e) {
            if (flag.get() || isCancellation(e)) {
                OperationStage failedAt = currentStage.get();
                RollbackOutcome rollback = attemptRollback(active, checkpoint, mutationMayHaveCommitted, telemetry,
                        operationId, changes, skipped, duplicates, issues.size(), errors, e);
                keepCheckpoint = rollback.attempted() && !rollback.succeeded();
                emit(telemetry, OperationProgress.stage(operationId, OperationStage.CANCELLED, false)
                        .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                                skipped, duplicates, issues.size(), errors));
                safeFailure(sourceKey, failureDescription(failedAt, "Оновлення скасовано", rollback));
                String message = rollback.attempted() && !rollback.succeeded()
                        ? "Оновлення скасовано; автоматичний відкат не завершено" : "Оновлення скасовано";
                throw new CatalogUpdateFailureException(message, e, failedAt, safeSource, localVersion,
                        mutationMayHaveCommitted, rollback.attempted(), rollback.succeeded());
            }
            OperationStage failedAt = currentStage.get();
            RollbackOutcome rollback = attemptRollback(active, checkpoint, mutationMayHaveCommitted, telemetry,
                    operationId, changes, skipped, duplicates, issues.size(), errors, e);
            keepCheckpoint = rollback.attempted() && !rollback.succeeded();
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.FAILED, false)
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors + 1));
            String root = ThrowableMessages.rootMessage(e);
            safeFailure(sourceKey, failureDescription(failedAt, root, rollback));
            throw new CatalogUpdateFailureException("Не вдалося оновити колекцію з мережі: " + root,
                    e, failedAt, safeSource, localVersion, mutationMayHaveCommitted,
                    rollback.attempted(), rollback.succeeded());
        } catch (Exception e) {
            OperationStage failedAt = currentStage.get();
            RollbackOutcome rollback = attemptRollback(active, checkpoint, mutationMayHaveCommitted, telemetry,
                    operationId, changes, skipped, duplicates, issues.size(), errors, e);
            keepCheckpoint = rollback.attempted() && !rollback.succeeded();
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.FAILED, false)
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors + 1));
            String root = ThrowableMessages.rootMessage(e);
            safeFailure(sourceKey, failureDescription(failedAt, root, rollback));
            throw new CatalogUpdateFailureException("Не вдалося оновити колекцію з мережі: " + root,
                    e, failedAt, safeSource, localVersion, mutationMayHaveCommitted,
                    rollback.attempted(), rollback.succeeded());
        } finally {
            for (Path path : downloaded) {
                if (path != null && path.startsWith(AppPaths.cacheDir())) {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                }
            }
            if (checkpoint != null && !keepCheckpoint) {
                try { CatalogUpdateRecoveryFiles.clear(active.getId()); } catch (Exception ignored) { }
            }
        }
    }


    private static void commitRecoveryCheckpoint(Collection collection, Path checkpoint) throws java.io.IOException {
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) return;

        // Marker deletion is strict because it is the commit boundary. Checkpoint deletion is only
        // cleanup: once the marker is gone the checkpoint is no longer authoritative and a leftover
        // file cannot trigger rollback on the next start.
        CatalogUpdateRecoveryFiles.deleteMarkerOnly(collection.getId());
        try { Files.deleteIfExists(checkpoint); } catch (java.io.IOException ignored) { }
    }

    private Path updateCheckpointPath(Collection collection, String operationId) {
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) {
            throw new IllegalArgumentException("Collection id is required for update recovery checkpoint");
        }
        // One deterministic checkpoint per collection makes an interrupted update recoverable on
        // the next process start without having to discover a random operation UUID.
        return CatalogUpdateRecoveryFiles.checkpoint(collection.getId()).toAbsolutePath().normalize();
    }

    private void recoverPendingUpdateBeforeNewAttempt(
            Collection collection,
            Consumer<OperationProgress> telemetry,
            String operationId) {
        if (collection == null || collection.getId() == null || collection.getId().isBlank()) return;
        String collectionId = collection.getId();
        if (!CatalogUpdateRecoveryFiles.isPending(collectionId)) return;
        if (collectionBackupPort == null) {
            throw new IllegalStateException("Знайдено незавершене попереднє оновлення, але recovery adapter недоступний");
        }

        Path checkpoint = CatalogUpdateRecoveryFiles.checkpoint(collectionId);
        if (!Files.isRegularFile(checkpoint)) {
            throw new IllegalStateException("Recovery marker існує, але SQLite checkpoint відсутній: " + checkpoint);
        }

        emit(telemetry, OperationProgress.stage(operationId, OperationStage.ROLLING_BACK, false)
                .withCurrentItem("Відновлення після аварійного завершення попереднього update"));
        try {
            collectionBackupPort.validateDatabaseFile(checkpoint);
            collectionBackupPort.restoreDatabaseSnapshot(collection, checkpoint);
            AtomicBoolean neverCancelRecovery = new AtomicBoolean(false);
            searchIndexer.rebuildIndex(neverCancelRecovery, p -> emit(telemetry,
                    OperationProgress.stage(operationId, OperationStage.ROLLING_BACK, false)
                            .withProgress(p.processed(), p.total())
                            .withCurrentItem("Відновлення пошукового індексу після crash")));
            if (statisticsRepository != null) {
                statisticsRepository.invalidate();
                statisticsRepository.refreshStatistics();
            }
            CatalogUpdateRecoveryFiles.clear(collectionId);
        } catch (Exception recoveryFailure) {
            throw new IllegalStateException(
                    "Не вдалося відновити колекцію після аварійно перерваного online update; checkpoint збережено",
                    recoveryFailure);
        }
    }

    private RollbackOutcome attemptRollback(
            Collection collection,
            Path checkpoint,
            boolean mutationMayHaveCommitted,
            Consumer<OperationProgress> telemetry,
            String operationId,
            ImportChangeAccumulator changes,
            long skipped,
            long duplicates,
            long warnings,
            long errors,
            Throwable originalFailure) {
        if (!mutationMayHaveCommitted || checkpoint == null || collectionBackupPort == null) {
            return RollbackOutcome.notAttempted();
        }

        emit(telemetry, OperationProgress.stage(operationId, OperationStage.ROLLING_BACK, false)
                .withCurrentItem("SQLite → Lucene → statistics")
                .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                        skipped, duplicates, warnings, errors));
        try {
            collectionBackupPort.restoreDatabaseSnapshot(collection, checkpoint);

            // Lucene/statistics are derived state. Rebuild them from the restored SQLite source of truth
            // even if the original failure happened before Lucene was touched: this makes rollback
            // deterministic across import, cancellation, index, statistics and source-state failures.
            AtomicBoolean neverCancelRecovery = new AtomicBoolean(false);
            searchIndexer.rebuildIndex(neverCancelRecovery, p -> emit(telemetry,
                    OperationProgress.stage(operationId, OperationStage.ROLLING_BACK, false)
                            .withProgress(p.processed(), p.total())
                            .withCurrentItem("Відновлення пошукового індексу")
                            .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                                    skipped, duplicates, warnings, errors)));
            if (statisticsRepository != null) {
                statisticsRepository.invalidate();
                statisticsRepository.refreshStatistics();
            }
            return RollbackOutcome.success();
        } catch (Exception rollbackFailure) {
            if (originalFailure != null) originalFailure.addSuppressed(rollbackFailure);
            return RollbackOutcome.failed(ThrowableMessages.rootMessage(rollbackFailure));
        }
    }

    private static String failureDescription(OperationStage stage, String message, RollbackOutcome rollback) {
        StringBuilder out = new StringBuilder();
        out.append(stage == null ? OperationStage.FAILED : stage).append(": ")
                .append(message == null || message.isBlank() ? "Невідома помилка" : message);
        if (rollback != null && rollback.attempted()) {
            out.append(rollback.succeeded() ? " · rollback=OK" : " · rollback=FAILED");
            if (!rollback.detail().isBlank()) out.append(" (").append(rollback.detail()).append(')');
        }
        return out.toString();
    }

    private record RollbackOutcome(boolean attempted, boolean succeeded, String detail) {
        private RollbackOutcome {
            detail = detail == null ? "" : detail;
        }
        static RollbackOutcome notAttempted() { return new RollbackOutcome(false, false, ""); }
        static RollbackOutcome success() { return new RollbackOutcome(true, true, ""); }
        static RollbackOutcome failed(String detail) { return new RollbackOutcome(true, false, detail); }
    }

    private static Path onlineBookStorageRoot(Collection collection) {
        if (collection.getRootFolder() != null) {
            return collection.getRootFolder().toAbsolutePath().normalize();
        }
        if (collection.getId() == null || collection.getId().isBlank()) {
            throw new IllegalStateException("Online-колекція не має stable ID для download root");
        }
        return AppPaths.downloadsDir().resolve(collection.getId()).toAbsolutePath().normalize();
    }

    private static void emitDownloadProgress(Consumer<OperationProgress> telemetry, String operationId, RemoteDownloadProgress progress) {
        if (progress == null) return;
        emit(telemetry, OperationProgress.stage(operationId, OperationStage.DOWNLOADING, true)
                .withBytes(progress.bytesProcessed(), progress.bytesTotal())
                .withCurrentItem(progress.currentItem()));
    }

    private static String safeDisplaySource(String source) {
        if (source == null || source.isBlank()) return "";
        try {
            return SensitiveDataSanitizer.sanitizeUri(URI.create(source));
        } catch (RuntimeException invalidUri) {
            return SensitiveDataSanitizer.sanitizeText(source);
        }
    }

    private void applyIncrementalIndex(ImportChangeSet changes, AtomicBoolean cancelFlag, Consumer<long[]> progress) {
        boolean begun = false;
        long total = (long) changes.deletedCount() + changes.insertedCount() + changes.updatedCount();
        long processed = 0;
        try {
            searchIndexer.beginAtomicUpdate();
            begun = true;
            notifyIndexProgress(progress, processed, total);
            for (String id : changes.deleted()) {
                checkCancelled(cancelFlag);
                searchIndexer.deleteBook(BookId.fromString(id));
                notifyIndexProgress(progress, ++processed, total);
            }

            Set<String> changed = new LinkedHashSet<>(changes.inserted());
            changed.addAll(changes.updated());
            List<String> ids = new ArrayList<>(changed);
            for (int start = 0; start < ids.size(); start += 400) {
                checkCancelled(cancelFlag);
                List<BookId> batchIds = ids.subList(start, Math.min(ids.size(), start + 400)).stream()
                        .map(BookId::fromString).toList();
                List<Book> books = bookQueryRepository.findByIds(batchIds);
                java.util.Map<String, Book> byId = new java.util.HashMap<>();
                for (Book book : books) if (book != null) byId.put(book.getId().asString(), book);

                // Count every requested ID exactly once so telemetry cannot stall on deleted/missing rows.
                for (BookId id : batchIds) {
                    checkCancelled(cancelFlag);
                    Book book = byId.get(id.asString());
                    if (book == null || book.isDeleted()) searchIndexer.deleteBook(id);
                    else searchIndexer.indexBook(book);
                    processed++;
                    if (processed % 250 == 0 || processed == total) notifyIndexProgress(progress, processed, total);
                }
            }
            checkCancelled(cancelFlag);
            searchIndexer.commit();
            notifyIndexProgress(progress, total, total);
        } catch (RuntimeException e) {
            if (begun) {
                try { searchIndexer.rollbackAtomicUpdate(); } catch (RuntimeException rollback) { e.addSuppressed(rollback); }
            }
            throw e;
        }
    }

    private static void notifyIndexProgress(Consumer<long[]> progress, long processed, long total) {
        if (progress == null) return;
        try { progress.accept(new long[] { processed, total }); } catch (RuntimeException ignored) { }
    }

    private static void checkCancelled(AtomicBoolean flag) {
        if ((flag != null && flag.get()) || Thread.currentThread().isInterrupted()) throw new UpdateCancelledException();
    }

    private static boolean isCancellation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String name = current.getClass().getSimpleName();
            if (name.contains("Cancelled") || name.contains("Cancellation")) return true;
            current = current.getCause();
        }
        return false;
    }

    private static void emit(Consumer<OperationProgress> listener, OperationProgress progress) {
        if (listener == null || progress == null) return;
        try { listener.accept(progress); } catch (RuntimeException ignored) { }
    }

    private void safeFailure(String sourceKey, String message) {
        try { sourceState.recordFailure(sourceKey, message); } catch (RuntimeException ignored) { }
    }

    private static boolean isFailure(ImportStatus status) {
        return status == ImportStatus.VALIDATION_FAILURE || status == ImportStatus.DOWNLOAD_FAILURE
                || status == ImportStatus.FORMAT_FAILURE || status == ImportStatus.IMPORT_FAILURE;
    }

    private RemoteCatalogPackage unchangedSingleFullSnapshot(RemoteCatalogUpdatePlan plan, String sourceKey) {
        if (plan == null || plan.packages() == null || plan.packages().size() != 1) return null;
        RemoteCatalogPackage pkg = plan.packages().getFirst();
        if (pkg == null || !pkg.fullSnapshot() || pkg.file() == null || pkg.metadata() == null) return null;
        String sha256 = pkg.metadata().sha256();
        if (sha256 == null || sha256.isBlank()) return null;
        return sourceState.matchesAppliedFingerprint(sourceKey, sha256) ? pkg : null;
    }

    private static String lastVersion(RemoteCatalogUpdatePlan plan) {
        if (plan.latestVersion() != null && !plan.latestVersion().isBlank()) return plan.latestVersion();
        String result = "";
        for (RemoteCatalogPackage pkg : plan.packages()) if (pkg.version() != null && !pkg.version().isBlank()) result = pkg.version();
        return result;
    }

    private static String profileType(String source) {
        return source != null && source.contains("alex80.github.io/mhl")
                ? CatalogSourceProfile.FLIBUSTA_MHL.sourceType() : "custom-http";
    }

    private static void appendIssuesBounded(List<ImportIssue> target, List<ImportIssue> source) {
        if (source == null || target.size() >= 1000) return;
        int remaining = 1000 - target.size();
        target.addAll(source.subList(0, Math.min(remaining, source.size())));
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }


    private static final class UpdateCancelledException extends RuntimeException { }
}
