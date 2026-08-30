package com.myhomelibcorp.application.usecase.collection;

import com.myhomelibcorp.application.catalog.CatalogSourceIdentity;
import com.myhomelibcorp.application.catalog.CatalogSourceProfile;
import com.myhomelibcorp.application.catalog.CatalogSourceState;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.diagnostics.ImportIssue;
import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.port.out.catalog.CatalogSourceStatePort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogDownloadPort;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogPackage;
import com.myhomelibcorp.application.port.out.download.RemoteCatalogUpdatePlan;
import com.myhomelibcorp.application.port.out.download.RemoteDownloadProgress;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.application.usecase.imports.ImportFileUseCase;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.security.SensitiveDataSanitizer;

import java.nio.file.Files;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final int changeTrackingLimit;

    public UpdateCollectionFromNetworkUseCase(
            RemoteCatalogDownloadPort downloader,
            ImportFileUseCase importer,
            CollectionLifecycleService lifecycle,
            CatalogSourceStatePort sourceState,
            SearchIndexer searchIndexer,
            BookQueryRepository bookQueryRepository) {
        this(downloader, importer, lifecycle, sourceState, searchIndexer, bookQueryRepository,
                ImportChangeAccumulator.DEFAULT_TRACKED_ID_LIMIT);
    }

    public UpdateCollectionFromNetworkUseCase(
            RemoteCatalogDownloadPort downloader,
            ImportFileUseCase importer,
            CollectionLifecycleService lifecycle,
            CatalogSourceStatePort sourceState,
            SearchIndexer searchIndexer,
            BookQueryRepository bookQueryRepository,
            int changeTrackingLimit) {
        this.downloader = downloader;
        this.importer = importer;
        this.lifecycle = lifecycle;
        this.sourceState = sourceState;
        this.searchIndexer = searchIndexer;
        this.bookQueryRepository = bookQueryRepository;
        this.changeTrackingLimit = ImportChangeAccumulator.normalizeLimit(changeTrackingLimit);
    }

    public ImportResult execute(Collection collection, String source, AtomicBoolean cancel, DoubleConsumer progress) {
        return execute(collection, source, cancel, progress, null);
    }

    public ImportResult execute(Collection collection, String source, AtomicBoolean cancel, DoubleConsumer progress,
                                Consumer<OperationProgress> operationProgress) {
        if (collection == null) throw new IllegalArgumentException("Колекцію не вибрано");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Не задано URL INPX/сервера");
        Collection active = lifecycle.getCurrentCollection();
        if (active == null || active.getId() == null || collection.getId() == null || !active.getId().equals(collection.getId())) {
            throw new IllegalStateException("Оновлювати з мережі можна лише активну колекцію");
        }

        AtomicBoolean flag = cancel == null ? new AtomicBoolean(false) : cancel;
        DoubleConsumer sink = progress == null ? p -> { } : progress;
        Consumer<OperationProgress> telemetry = operationProgress == null ? p -> { } : operationProgress;
        String operationId = "catalog-update-" + UUID.randomUUID();
        long operationStartedNanos = System.nanoTime();
        String sourceKey = CatalogSourceIdentity.remoteCollection(collection.getId());
        CatalogSourceState durable = sourceState.get(sourceKey);
        String localVersion = durable.appliedVersion();
        List<Path> downloaded = new ArrayList<>();

        long imported = 0, skipped = 0, duplicates = 0, errors = 0;
        ImportChangeAccumulator changes = new ImportChangeAccumulator(changeTrackingLimit);
        List<ImportIssue> issues = new ArrayList<>();

        try {
            String safeSource = safeDisplaySource(source.trim());
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.CHECKING_SERVER, true)
                    .withCurrentItem(safeSource));
            sink.accept(0.01);
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

            emit(telemetry, OperationProgress.stage(operationId, OperationStage.VALIDATING, true)
                    .withProgress(plan.packages().size(), plan.packages().size()));

            for (int packageIndex = 0; packageIndex < plan.packages().size(); packageIndex++) {
                RemoteCatalogPackage pkg = plan.packages().get(packageIndex);
                if (pkg == null || pkg.file() == null) throw new IllegalStateException("Порожній пакет оновлення");
                downloaded.add(pkg.file());
                checkCancelled(flag);
                sourceState.recordDownloaded(sourceKey, pkg.metadata().etag(), pkg.metadata().lastModified(),
                        pkg.metadata().sha256(), pkg.metadata().datasetSchema());

                String packageLabel = (pkg.fullSnapshot() ? "FULL · " : "DELTA · ") + pkg.file().getFileName();
                emit(telemetry, OperationProgress.stage(operationId, OperationStage.READING_CATALOG, true)
                        .withProgress(packageIndex, plan.packages().size())
                        .withCurrentItem(packageLabel)
                        .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                                skipped, duplicates, issues.size(), errors));

                // Remote INPX packages live in cache/catalog-updates, but book storage metadata must
                // point at the permanent collection/download root. Persisting pkg.file().getParent()
                // made every remote book look for online.zip/extra.zip inside the temporary catalog cache.
                Path root = onlineBookStorageRoot(active);
                double importStart = 0.35 + 0.45 * packageIndex / plan.packages().size();
                double importSpan = 0.45 / plan.packages().size();
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
                        .operationProgressListener(p -> emit(telemetry, p.withCurrentItem(packageLabel)))
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
                changes.merge(current.changes());
                appendIssuesBounded(issues, current.issues());
            }

            emit(telemetry, OperationProgress.stage(operationId, OperationStage.APPLYING_DELETIONS, true)
                    .withProgress(changes.deletedCount(), changes.deletedCount())
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors));

            // Freeze the counters before passing them into callbacks. Import state is complete at this point,
            // so telemetry remains deterministic while Lucene works on background/bounded batches.
            final ImportChangeSet indexChanges = changes.snapshot();
            final long indexSkipped = skipped;
            final long indexDuplicates = duplicates;
            final long indexErrors = errors;
            final long indexWarnings = issues.size();

            // Database packages are applied first. Only after the search index is safely finalized
            // do we advance applied_version. Cancellation or index failure leaves the previous committed index available.
            sink.accept(0.82);
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
            sink.accept(0.97);
            checkCancelled(flag);

            emit(telemetry, OperationProgress.stage(operationId, OperationStage.FINALIZING, false)
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors));
            String applied = lastVersion(plan);
            sourceState.recordApplied(sourceKey, applied);
            sink.accept(1.0);
            ImportStatus status = errors > 0 || !issues.isEmpty() ? ImportStatus.SUCCESS_WITH_WARNINGS : ImportStatus.SUCCESS;
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.COMPLETED, false)
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors));
            return new ImportResult(imported, skipped, duplicates, errors, elapsedMillis(operationStartedNanos), status, indexChanges, issues);
        } catch (UpdateCancelledException e) {
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.CANCELLED, false)
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors));
            safeFailure(sourceKey, "Оновлення скасовано");
            throw new IllegalStateException("Оновлення скасовано", e);
        } catch (RuntimeException e) {
            if (flag.get() || isCancellation(e)) {
                emit(telemetry, OperationProgress.stage(operationId, OperationStage.CANCELLED, false)
                        .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                                skipped, duplicates, issues.size(), errors));
                safeFailure(sourceKey, "Оновлення скасовано");
                throw new IllegalStateException("Оновлення скасовано", e);
            }
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.FAILED, false)
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors + 1));
            safeFailure(sourceKey, rootMessage(e));
            throw e;
        } catch (Exception e) {
            emit(telemetry, OperationProgress.stage(operationId, OperationStage.FAILED, false)
                    .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, issues.size(), errors + 1));
            safeFailure(sourceKey, rootMessage(e));
            throw new IllegalStateException("Не вдалося оновити колекцію з мережі: " + rootMessage(e), e);
        } finally {
            for (Path path : downloaded) {
                if (path != null && path.startsWith(AppPaths.cacheDir())) {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                }
            }
        }
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

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? error.getClass().getSimpleName() : current.getMessage();
    }

    private static final class UpdateCancelledException extends RuntimeException { }
}
