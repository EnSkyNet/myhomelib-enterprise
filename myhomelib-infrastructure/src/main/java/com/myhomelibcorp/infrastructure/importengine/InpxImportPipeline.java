package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.shared.util.Sha256Support;
import com.myhomelibcorp.application.catalog.CatalogBookSnapshot;
import com.myhomelibcorp.application.catalog.CatalogSourceIdentity;
import com.myhomelibcorp.application.catalog.CatalogSyncSession;
import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.port.out.catalog.CatalogUpdateTrackingPort;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.author.AuthorNameKey;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.importengine.InpxBookNormalizer.NormalizedBook;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class InpxImportPipeline {
    private static final long AUTHOR_PRELOAD_MIN_RECORDS = 100_000L;
    private static final long SELECTIVE_PREVIEW_MIN_RECORDS = 100_000L;
    private final InpxReader reader;
    private final JdbcBatchWriter batchWriter;
    private final BulkImportOptimizer bulkOptimizer;
    private final CollectionManager collectionManager;
    private final CatalogUpdateTrackingPort catalogUpdateTrackingPort;
    private final ImportIndexLifecycle importIndexLifecycle;

    @Value("${app.import.change-tracking-limit:50000}")
    private int changeTrackingLimit;

    @Value("${app.import.author-cache-size:250000}")
    private int authorCacheSize;

    @Value("${app.import.online-batch-size:5000}")
    private int onlineBatchSize;

    private Map<AuthorNameKey, String> authorCache;
    private Map<String, String> genreCache;

    private JdbcTemplate getJdbcTemplate() { return collectionManager.getCurrentJdbcTemplate(); }

    private int effectiveChangeTrackingLimit() {
        return ImportChangeAccumulator.normalizeLimit(changeTrackingLimit);
    }

    public long importFile(Path file, int batchSize, Path rootDirectory) {
        return importFileWithResult(file, batchSize, rootDirectory, null, null, null, null, null).imported();
    }

    public long importFile(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag) {
        return importFileWithResult(file, batchSize, rootDirectory, cancelFlag, null, null, null, null).imported();
    }

    public long importFile(Path file, int batchSize, Path rootDirectory, AtomicBoolean cancelFlag,
                           String catalogSourceKey, String catalogSourceLocation) {
        return importFileWithResult(
                file, batchSize, rootDirectory, cancelFlag, catalogSourceKey, catalogSourceLocation, null, null
        ).imported();
    }

    public ImportResult importFileWithResult(
            Path file,
            int batchSize,
            Path rootDirectory,
            AtomicBoolean cancelFlag,
            String catalogSourceKey,
            String catalogSourceLocation,
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer) {
        return importFileWithResult(file, batchSize, rootDirectory, cancelFlag, catalogSourceKey,
                catalogSourceLocation, true, progressListener, statusConsumer);
    }

    public ImportResult importFileWithResult(
            Path file,
            int batchSize,
            Path rootDirectory,
            AtomicBoolean cancelFlag,
            String catalogSourceKey,
            String catalogSourceLocation,
            boolean catalogFullSnapshot,
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer) {
        return importFileWithResult(file, batchSize, rootDirectory, cancelFlag, catalogSourceKey, catalogSourceLocation,
                catalogFullSnapshot, progressListener, statusConsumer, null, null);
    }

    public ImportResult importFileWithResult(
            Path file,
            int batchSize,
            Path rootDirectory,
            AtomicBoolean cancelFlag,
            String catalogSourceKey,
            String catalogSourceLocation,
            boolean catalogFullSnapshot,
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer,
            String operationId,
            Consumer<OperationProgress> operationProgressListener) {

        long startedAt = System.currentTimeMillis();
        String opId = operationId == null || operationId.isBlank() ? "import-" + UUID.randomUUID() : operationId;
        int requestedBatch = Math.max(50, Math.min(batchSize <= 0 ? 1000 : batchSize, 10_000));
        Path root = rootDirectory != null ? rootDirectory.toAbsolutePath().normalize()
                : (file.getParent() != null
                    ? file.getParent().toAbsolutePath().normalize()
                    : Path.of(".").toAbsolutePath().normalize());
        String sourceKey = catalogSourceKey != null && !catalogSourceKey.isBlank()
                ? catalogSourceKey.trim()
                : CatalogSourceIdentity.localInpx(file, root);
        String sourceLocation = catalogSourceLocation != null && !catalogSourceLocation.isBlank()
                ? catalogSourceLocation.trim()
                : file.toAbsolutePath().normalize().toString();

        notifyStatus(statusConsumer, "Підготовка INPX...");
        notifyProgress(progressListener, 0.0);
        notifyOperation(operationProgressListener, OperationProgress.stage(opId, OperationStage.READING_CATALOG, true)
                .withCurrentItem(file.getFileName() == null ? file.toString() : file.getFileName().toString()));

        boolean onlineCollection = sourceKey.startsWith("remote-collection:");
        int effectiveBatch = onlineCollection
                ? Math.max(requestedBatch, Math.max(1_000, Math.min(onlineBatchSize, 10_000)))
                : requestedBatch;
        long totalRecords = reader.count(file, cancelFlag, onlineCollection);
        if (isCancelled(cancelFlag)) {
            notifyStatus(statusConsumer, "Імпорт INPX скасовано під час аналізу індексу");
            notifyOperation(operationProgressListener, OperationProgress.stage(opId, OperationStage.CANCELLED, false));
            return ImportResult.cancelled(System.currentTimeMillis() - startedAt);
        }
        if (totalRecords < 0) {
            throw new IllegalStateException("Не вдалося проаналізувати INPX: " + file);
        }

        notifyStatus(statusConsumer, String.format(Locale.ROOT,
                "Аналіз індексу: %,d записів", totalRecords));
        notifyOperation(operationProgressListener, new OperationProgress(opId, OperationStage.VALIDATING,
                0, totalRecords, 0, -1, 0, 0, 0, 0, 0, 0, 0,
                file.getFileName() == null ? file.toString() : file.getFileName().toString(), true));

        String sourceFingerprint = sha256(file, cancelFlag);
        if (sourceFingerprint == null || isCancelled(cancelFlag)) {
            log.info("INPX fingerprinting cancelled before import: {}", file);
            notifyStatus(statusConsumer, "Імпорт INPX скасовано");
            notifyOperation(operationProgressListener, OperationProgress.stage(opId, OperationStage.CANCELLED, false));
            return ImportResult.cancelled(System.currentTimeMillis() - startedAt);
        }

        boolean databaseInitiallyEmpty = isBookCatalogEmpty();
        boolean authorTableInitiallyEmpty = databaseInitiallyEmpty && isAuthorCatalogEmpty();
        Set<Path> indexedLocalFiles = onlineCollection
                ? indexExistingLocalFiles(root, cancelFlag)
                : null;
        if (onlineCollection) {
            log.info("Online local-file index prepared: {} existing files under {}",
                    indexedLocalFiles == null ? 0 : indexedLocalFiles.size(), root);
        }

        log.info("Starting INPX import: {} (root: {}, batch: {}, sourceKey: {}, records: {}, emptyCatalog={}, emptyAuthors={})",
                file, root, effectiveBatch, sourceKey, totalRecords, databaseInitiallyEmpty, authorTableInitiallyEmpty);

        // Never preload SELECT * FROM authors. The bounded import cache may later preload only
        // the persistent ID + normalized name columns when the complete author table fits the
        // configured capacity; otherwise it stays incremental and uses the exact DB fallback.
        this.authorCache = newBoundedAuthorCache(authorCacheSize);
        this.genreCache = buildGenreCache();

        boolean optimized = false;
        ImportOutcome outcome;
        try {
            bulkOptimizer.enableBulkInsertMode();
            optimized = true;

            var dataSource = collectionManager.getCurrentDataSource();
            if (dataSource != null) {
                logPoolState(dataSource, "before-transaction");
                TransactionTemplate transaction =
                        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                long transactionStarted = System.nanoTime();
                try {
                    outcome = transaction.execute(status -> {
                        log.info("INPX transaction started; connection acquired by transaction manager");
                        ImportOutcome current = importTransactional(
                                file, effectiveBatch, root, cancelFlag, sourceKey, sourceLocation,
                                sourceFingerprint, totalRecords, catalogFullSnapshot, onlineCollection, databaseInitiallyEmpty, authorTableInitiallyEmpty, indexedLocalFiles,
                                progressListener, statusConsumer, opId, operationProgressListener);
                        if (current.cancelled()) {
                            status.setRollbackOnly();
                            log.info("INPX import cancelled; transaction marked rollback-only after {} parsed records",
                                    current.processed());
                        }
                        return current;
                    });
                    long txMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - transactionStarted);
                    if (outcome != null && outcome.cancelled()) {
                        log.info("INPX transaction rolled back; duration={} ms", txMs);
                    } else {
                        log.info("INPX transaction committed; duration={} ms", txMs);
                    }
                } catch (RuntimeException transactionFailure) {
                    long txMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - transactionStarted);
                    log.warn("INPX transaction rolled back after failure; duration={} ms; cause={}",
                            txMs, transactionFailure.toString());
                    throw transactionFailure;
                } finally {
                    // TransactionTemplate has completed here, so its connection must already be returned to Hikari.
                    logPoolState(dataSource, "after-transaction/connection-returned");
                }
                if (outcome == null) {
                    outcome = new ImportOutcome(0, 0, 0, 0, 0, 0, 0, 0, false, false,
                            ImportChangeSet.empty(!catalogFullSnapshot));
                }
            } else {
                outcome = importTransactional(
                        file, effectiveBatch, root, cancelFlag, sourceKey, sourceLocation,
                        sourceFingerprint, totalRecords, catalogFullSnapshot, onlineCollection, databaseInitiallyEmpty, authorTableInitiallyEmpty, indexedLocalFiles,
                        progressListener, statusConsumer, opId, operationProgressListener);
            }
        } finally {
            if (optimized) bulkOptimizer.disableBulkInsertMode();
            if (authorCache != null) authorCache.clear();
            if (genreCache != null) genreCache.clear();
            authorCache = null;
            genreCache = null;
        }

        long duration = System.currentTimeMillis() - startedAt;
        if (outcome.cancelled()) {
            notifyStatus(statusConsumer, "Імпорт INPX скасовано; зміни відкотено");
            notifyOperation(operationProgressListener, new OperationProgress(opId, OperationStage.CANCELLED,
                    outcome.processed(), totalRecords, 0, -1, 0, 0, 0, outcome.skipped(), outcome.duplicates(),
                    0, outcome.errors(), "", false));
            return new ImportResult(0, outcome.skipped(), outcome.duplicates(), outcome.errors(), duration,
                    ImportStatus.CANCELLED, ImportChangeSet.empty(false), List.of(),
                    outcome.withoutAuthor(), outcome.withoutGenre(), outcome.explicitlyDeleted());
        }

        notifyProgress(progressListener, 1.0);
        notifyOperation(operationProgressListener, new OperationProgress(opId, OperationStage.IMPORTING,
                outcome.processed(), totalRecords, 0, -1, outcome.changes().insertedCount(),
                outcome.changes().updatedCount(), outcome.changes().deletedCount(), outcome.skipped(),
                outcome.duplicates(), 0, outcome.errors(), "", true));
        if (outcome.sourceUnchanged()) {
            notifyStatus(statusConsumer, "Каталог INPX не змінився; повторний імпорт пропущено");
        } else {
            notifyStatus(statusConsumer, String.format(Locale.ROOT,
                    "Імпорт INPX завершено: %,d / %,d; без автора: %,d; без жанру: %,d; явно видалено: %,d; помилок: %,d",
                    outcome.imported(), totalRecords, outcome.withoutAuthor(), outcome.withoutGenre(),
                    outcome.explicitlyDeleted(), outcome.errors()));
        }
        log.info("INPX import completed: imported={}, skipped={}, duplicates={}, errors={}, withoutAuthor={}, withoutGenre={}, explicitlyDeleted={}, durationMs={}",
                outcome.imported(), outcome.skipped(), outcome.duplicates(), outcome.errors(),
                outcome.withoutAuthor(), outcome.withoutGenre(), outcome.explicitlyDeleted(), duration);
        return new ImportResult(
                outcome.imported(), outcome.skipped(), outcome.duplicates(), outcome.errors(), duration,
                outcome.errors() > 0 ? ImportStatus.SUCCESS_WITH_WARNINGS : ImportStatus.SUCCESS,
                outcome.changes(), List.of(), outcome.withoutAuthor(), outcome.withoutGenre(), outcome.explicitlyDeleted());
    }

    /**
     * Executes the catalog mutation as one database transaction when a datasource is active.
     */
    private ImportOutcome importTransactional(
            Path file,
            int effectiveBatch,
            Path root,
            AtomicBoolean cancelFlag,
            String sourceKey,
            String sourceLocation,
            String sourceFingerprint,
            long totalRecords,
            boolean catalogFullSnapshot,
            boolean onlineCollection,
            boolean databaseInitiallyEmpty,
            boolean authorTableInitiallyEmpty,
            Set<Path> indexedLocalFiles,
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer,
            String operationId,
            Consumer<OperationProgress> operationProgressListener) {

        boolean tracked = collectionManager.hasActiveCollection();
        CatalogSyncSession syncSession = tracked
                ? catalogUpdateTrackingPort.beginSync(sourceKey, sourceLocation, sourceFingerprint)
                : new CatalogSyncSession(
                        CatalogSourceIdentity.stableId(sourceKey), sourceKey, 1L, sourceFingerprint, true, true);
        if (catalogFullSnapshot && onlineCollection && tracked
                && !syncSession.initialBaseline() && !syncSession.sourceChanged()) {
            log.info("INPX source fingerprint unchanged; skipping replay of {} records for {}",
                    totalRecords, sourceKey);
            notifyProgress(progressListener, 1.0);
            notifyStatus(statusConsumer, "Каталог INPX не змінився; повторний імпорт пропущено");
            notifyOperation(operationProgressListener, new OperationProgress(operationId, OperationStage.IMPORTING,
                    totalRecords, totalRecords, 0, -1, 0, 0, 0, totalRecords, 0, 0, 0, "", true));
            return new ImportOutcome(0, totalRecords, 0, 0, 0, 0, 0, totalRecords,
                    false, true, ImportChangeSet.empty(true));
        }
        boolean fastInitialBaseline = catalogFullSnapshot && databaseInitiallyEmpty && syncSession.initialBaseline();
        if (fastInitialBaseline) {
            log.info("Initial catalog baseline fast-path enabled: search-state lookups/fingerprints are deferred to Lucene rebuild");
        }
        // INPX deletion state is record-driven: only an explicit DEL marker may mark a book deleted.
        // Never infer deletion from absence in one snapshot; a partial/corrupt catalog must not hide
        // previously imported books. Catalog tracking still records revisions for rows that are present.

        // Preloading the complete author table only pays off for a large full online snapshot.
        // A small delta/local import must not spend hundreds of milliseconds reading tens of
        // thousands of unrelated authors before touching its few records.
        if (shouldPreloadAuthorCache(onlineCollection, catalogFullSnapshot, totalRecords)) {
            preloadAuthorCacheIfFits(authorTableInitiallyEmpty);
        }

        String sourceMarker = sourceKey.startsWith("remote-collection:")
                ? "catalog:" + syncSession.sourceId()
                : InpxBookNormalizer.sourceMarker(file, root);
        InpxBookNormalizer normalizer = new InpxBookNormalizer(authorCache, genreCache);

        Map<AuthorNameKey, Author> pendingAuthors = new LinkedHashMap<>();
        Map<String, Genre> pendingGenres = new LinkedHashMap<>();
        Map<String, Boolean> localCache = new HashMap<>();
        List<NormalizedBook> books = new ArrayList<>(effectiveBatch);

        long imported = 0;
        long skipped = 0;
        long duplicates = 0;
        long errors = 0;
        long withoutAuthor = 0;
        long withoutGenre = 0;
        long explicitlyDeleted = 0;
        long processed = 0;
        long lastReported = 0;
        ImportIndexLifecycle.SuspendedIndexes suspendedIndexes = ImportIndexLifecycle.SuspendedIndexes.empty();
        boolean cancelled = false;
        ImportChangeAccumulator changes = new ImportChangeAccumulator(effectiveChangeTrackingLimit());
        boolean selectivePreviewFastPath = shouldUseSelectivePreviewFastPath(
                catalogFullSnapshot, onlineCollection, fastInitialBaseline, totalRecords, indexedLocalFiles);
        if (selectivePreviewFastPath) {
            log.info("Changed full-snapshot preview fast-path enabled: unchanged rows skip full author/genre normalization");
        }

        notifyOperation(operationProgressListener, new OperationProgress(operationId, OperationStage.IMPORTING,
                0, totalRecords, 0, -1, 0, 0, 0, 0, 0, 0, 0, "", true));

        try {
            if (catalogFullSnapshot) {
                suspendedIndexes = importIndexLifecycle.suspendForFullSnapshot(fastInitialBaseline);
            }
            if (selectivePreviewFastPath) {
                return importChangedFullSnapshotWithPreview(
                        file, effectiveBatch, root, cancelFlag, sourceMarker, normalizer, syncSession, tracked,
                        changes, totalRecords, indexedLocalFiles, progressListener, statusConsumer, operationId,
                        operationProgressListener);
            }

            Iterator<InpxRecord> iterator = reader.read(file, onlineCollection);
            try {
                while (iterator.hasNext()) {
                    if (isCancelled(cancelFlag)) {
                        cancelled = true;
                        break;
                    }

                    InpxRecord raw = iterator.next();
                    processed++;

                    NormalizedBook row = normalizer.normalize(
                            raw, pendingAuthors, pendingGenres, root, localCache, sourceMarker, onlineCollection,
                            indexedLocalFiles, !fastInitialBaseline);
                    if (row == null) {
                        errors++;
                    } else {
                        if (row.withoutAuthor()) withoutAuthor++;
                        if (row.withoutGenre()) withoutGenre++;
                        if (row.explicitlyDeleted()) explicitlyDeleted++;
                        books.add(row);
                    }

                    if (books.size() >= effectiveBatch) {
                        int batchCount = books.size();
                        flush(books, pendingAuthors, pendingGenres, syncSession, tracked, changes, fastInitialBaseline, authorTableInitiallyEmpty);
                        imported += batchCount;
                        books.clear();
                    }

                    if (processed - lastReported >= 1000 || processed == totalRecords) {
                        reportProgress(progressListener, statusConsumer, operationProgressListener, operationId,
                                processed, totalRecords, changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                                skipped, duplicates, errors);
                        lastReported = processed;
                    }
                }
            } finally {
                InpxReader.closeIterator(iterator);
            }

            if (!cancelled && !books.isEmpty()) {
                int batchCount = books.size();
                flush(books, pendingAuthors, pendingGenres, syncSession, tracked, changes, fastInitialBaseline, authorTableInitiallyEmpty);
                imported += batchCount;
                books.clear();
            }

            if (!cancelled && processed != lastReported) {
                reportProgress(progressListener, statusConsumer, operationProgressListener, operationId,
                        processed, totalRecords, changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                        skipped, duplicates, errors);
            }

            ImportChangeSet changeSet = changes.snapshot();
            return new ImportOutcome(imported, skipped, duplicates, errors, withoutAuthor, withoutGenre,
                    explicitlyDeleted, processed, cancelled, false, changeSet);
        } finally {
            importIndexLifecycle.restore(suspendedIndexes);
        }
    }

    private record ImportOutcome(
            long imported,
            long skipped,
            long duplicates,
            long errors,
            long withoutAuthor,
            long withoutGenre,
            long explicitlyDeleted,
            long processed,
            boolean cancelled,
            boolean sourceUnchanged,
            ImportChangeSet changes) {
    }

    public long importFile(Path file, int batchSize) {
        return importFile(file, batchSize, null, null, null, null);
    }

    private static boolean shouldUseSelectivePreviewFastPath(boolean catalogFullSnapshot,
                                                             boolean onlineCollection,
                                                             boolean fastInitialBaseline,
                                                             long totalRecords,
                                                             Set<Path> indexedLocalFiles) {
        // The preview path intentionally starts with the safest case: a large changed online
        // snapshot whose library root contains no local files. Then unchanged catalog rows cannot
        // acquire a new local=true state, so author/genre/domain normalization is needed only for
        // new or metadata-changed books. Collections with local files keep the proven full path.
        return catalogFullSnapshot && onlineCollection && !fastInitialBaseline
                && totalRecords >= SELECTIVE_PREVIEW_MIN_RECORDS
                && indexedLocalFiles != null && indexedLocalFiles.isEmpty();
    }

    private ImportOutcome importChangedFullSnapshotWithPreview(
            Path file,
            int effectiveBatch,
            Path root,
            AtomicBoolean cancelFlag,
            String sourceMarker,
            InpxBookNormalizer normalizer,
            CatalogSyncSession syncSession,
            boolean tracked,
            ImportChangeAccumulator changes,
            long totalRecords,
            Set<Path> indexedLocalFiles,
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer,
            String operationId,
            Consumer<OperationProgress> operationProgressListener) {

        Map<AuthorNameKey, Author> pendingAuthors = new LinkedHashMap<>();
        Map<String, Genre> pendingGenres = new LinkedHashMap<>();
        Map<String, Boolean> localCache = new HashMap<>();
        List<InpxBookNormalizer.CatalogPreview> previews = new ArrayList<>(effectiveBatch);

        long imported = 0;
        long skipped = 0;
        long duplicates = 0;
        long errors = 0;
        long withoutAuthor = 0;
        long withoutGenre = 0;
        long explicitlyDeleted = 0;
        long processed = 0;
        long lastReported = 0;
        boolean cancelled = false;

        Iterator<InpxRecord> iterator = reader.read(file, true);
        try {
            while (iterator.hasNext()) {
                if (isCancelled(cancelFlag)) {
                    cancelled = true;
                    break;
                }
                InpxRecord raw = iterator.next();
                processed++;
                InpxBookNormalizer.CatalogPreview preview = normalizer.preview(raw, sourceMarker);
                if (preview == null) {
                    errors++;
                } else {
                    previews.add(preview);
                }

                if (previews.size() >= effectiveBatch) {
                    PreviewFlushResult batch = flushPreviewBatch(previews, pendingAuthors, pendingGenres,
                            normalizer, root, localCache, sourceMarker, indexedLocalFiles, syncSession, tracked, changes);
                    imported += batch.accepted();
                    errors += batch.errors();
                    withoutAuthor += batch.withoutAuthor();
                    withoutGenre += batch.withoutGenre();
                    explicitlyDeleted += batch.explicitlyDeleted();
                    previews.clear();
                }

                if (processed - lastReported >= 1000 || processed == totalRecords) {
                    reportProgress(progressListener, statusConsumer, operationProgressListener, operationId,
                            processed, totalRecords, changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                            skipped, duplicates, errors);
                    lastReported = processed;
                }
            }
        } finally {
            InpxReader.closeIterator(iterator);
        }

        if (!cancelled && !previews.isEmpty()) {
            PreviewFlushResult batch = flushPreviewBatch(previews, pendingAuthors, pendingGenres,
                    normalizer, root, localCache, sourceMarker, indexedLocalFiles, syncSession, tracked, changes);
            imported += batch.accepted();
            errors += batch.errors();
            withoutAuthor += batch.withoutAuthor();
            withoutGenre += batch.withoutGenre();
            explicitlyDeleted += batch.explicitlyDeleted();
            previews.clear();
        }

        if (!cancelled && processed != lastReported) {
            reportProgress(progressListener, statusConsumer, operationProgressListener, operationId,
                    processed, totalRecords, changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                    skipped, duplicates, errors);
        }
        return new ImportOutcome(imported, skipped, duplicates, errors, withoutAuthor, withoutGenre,
                explicitlyDeleted, processed, cancelled, false, changes.snapshot());
    }

    private record PreviewFlushResult(long accepted, long errors, long withoutAuthor,
                                      long withoutGenre, long explicitlyDeleted) { }

    private PreviewFlushResult flushPreviewBatch(
            List<InpxBookNormalizer.CatalogPreview> previews,
            Map<AuthorNameKey, Author> pendingAuthors,
            Map<String, Genre> pendingGenres,
            InpxBookNormalizer normalizer,
            Path root,
            Map<String, Boolean> localCache,
            String sourceMarker,
            Set<Path> indexedLocalFiles,
            CatalogSyncSession syncSession,
            boolean tracked,
            ImportChangeAccumulator changes) {
        if (previews.isEmpty()) return new PreviewFlushResult(0, 0, 0, 0, 0);

        List<String> ids = new ArrayList<>(previews.size());
        for (InpxBookNormalizer.CatalogPreview preview : previews) {
            ids.add(preview.catalogSnapshot().bookId());
        }
        Map<String, ExistingSearchState> existing = loadExistingSearchStates(ids);
        List<NormalizedBook> persistenceBooks = new ArrayList<>();
        List<SearchFingerprintRow> searchStateRows = new ArrayList<>();
        List<CatalogBookSnapshot> seenSnapshots = tracked ? new ArrayList<>(previews.size()) : List.of();

        long accepted = 0;
        long errors = 0;
        long withoutAuthor = 0;
        long withoutGenre = 0;
        long explicitlyDeleted = 0;

        for (InpxBookNormalizer.CatalogPreview preview : previews) {
            CatalogBookSnapshot previewSnapshot = preview.catalogSnapshot();
            String id = previewSnapshot.bookId();
            ExistingSearchState old = existing.get(id);
            // The fast-path guard proves there are no local files under the online library root.
            // Existing local=true is intentionally preserved by requiresBookPersistence(false, old).
            boolean needsPersistence = requiresBookPersistence(previewSnapshot.catalogFingerprint(), false, old);
            NormalizedBook normalized = null;
            if (needsPersistence) {
                normalized = normalizer.normalize(
                        preview.raw(), pendingAuthors, pendingGenres, root, localCache, sourceMarker,
                        true, indexedLocalFiles, true);
                if (normalized == null) {
                    errors++;
                    continue;
                }
            }

            CatalogBookSnapshot snapshot = normalized == null ? previewSnapshot : normalized.catalogSnapshot();
            boolean incomingDeleted = normalized == null
                    ? preview.deleted()
                    : ((Number) normalized.row()[15]).intValue() != 0;
            boolean incomingLocal = normalized != null && ((Number) normalized.row()[16]).intValue() != 0;
            String searchFingerprint;
            if (normalized != null) {
                searchFingerprint = normalized.searchFingerprint();
            } else if (shouldComputePreviewSearchFingerprint(
                    incomingDeleted, snapshot.catalogFingerprint(), old)) {
                searchFingerprint = normalizer.previewSearchFingerprint(preview);
            } else {
                // The complete catalog fingerprint is unchanged. With the current search model,
                // the stored search fingerprint is therefore still valid; legacy rows without a
                // search fingerprint keep the existing catalog-fingerprint fallback semantics.
                searchFingerprint = old == null ? null : old.searchFingerprint();
            }

            classifySearchChange(id, incomingDeleted, searchFingerprint,
                    snapshot.catalogFingerprint(), old, changes);
            if (normalized != null && requiresBookPersistence(snapshot.catalogFingerprint(), incomingLocal, old)) {
                persistenceBooks.add(normalized);
            } else if (tracked) {
                seenSnapshots.add(snapshot);
            }
            if (requiresSearchStatePersistence(incomingDeleted, searchFingerprint,
                    snapshot.catalogFingerprint(), old)) {
                searchStateRows.add(new SearchFingerprintRow(id, searchFingerprint));
            }

            accepted++;
            if (preview.withoutAuthor()) withoutAuthor++;
            if (preview.withoutGenre()) withoutGenre++;
            if (preview.deleted()) explicitlyDeleted++;
        }

        Map<String, String> authorResolution = flushPendingEntities(pendingAuthors, pendingGenres, false);
        if (!persistenceBooks.isEmpty()) {
            batchWriter.batchInsertFull(persistenceBooks.stream().map(NormalizedBook::row).toList(), authorResolution);
        }
        persistSearchFingerprintRows(searchStateRows);
        if (tracked) {
            if (!persistenceBooks.isEmpty()) {
                catalogUpdateTrackingPort.recordImportedBooks(
                        syncSession, persistenceBooks.stream().map(NormalizedBook::catalogSnapshot).toList());
            }
            if (!seenSnapshots.isEmpty()) {
                catalogUpdateTrackingPort.recordSeenBooks(syncSession, seenSnapshots);
            }
        }
        return new PreviewFlushResult(accepted, errors, withoutAuthor, withoutGenre, explicitlyDeleted);
    }

    private void flush(
            List<NormalizedBook> books,
            Map<AuthorNameKey, Author> pendingAuthors,
            Map<String, Genre> pendingGenres,
            CatalogSyncSession syncSession,
            boolean tracked,
            ImportChangeAccumulator changes,
            boolean fastInitialBaseline,
            boolean authorTableInitiallyEmpty) {
        boolean fastAuthorInsert = fastInitialBaseline && authorTableInitiallyEmpty && !authorCacheHasEvicted();
        Map<String, String> authorResolution = flushPendingEntities(pendingAuthors, pendingGenres, fastAuthorInsert);
        BatchClassification classification = null;
        if (fastInitialBaseline) {
            classifyInitialSearchChanges(books, changes);
            batchWriter.batchInsertFull(books.stream().map(NormalizedBook::row).toList(), authorResolution);
        } else {
            classification = classifySearchChanges(books, changes, tracked);
            if (!classification.persistenceBooks().isEmpty()) {
                batchWriter.batchInsertFull(
                        classification.persistenceBooks().stream().map(NormalizedBook::row).toList(),
                        authorResolution);
            }
            persistSearchFingerprints(classification.searchStateBooks());
        }
        if (tracked) {
            if (fastInitialBaseline) {
                catalogUpdateTrackingPort.recordImportedBooks(
                        syncSession, books.stream().map(NormalizedBook::catalogSnapshot).toList());
            } else {
                if (!classification.persistenceBooks().isEmpty()) {
                    catalogUpdateTrackingPort.recordImportedBooks(
                            syncSession, classification.persistenceBooks().stream()
                                    .map(NormalizedBook::catalogSnapshot).toList());
                }
                if (!classification.seenSnapshots().isEmpty()) {
                    catalogUpdateTrackingPort.recordSeenBooks(syncSession, classification.seenSnapshots());
                }
            }
        }
    }

    private static void classifyInitialSearchChanges(List<NormalizedBook> books, ImportChangeAccumulator changes) {
        for (NormalizedBook book : books) {
            Object[] row = book.row();
            String id = (String) row[0];
            boolean deleted = ((Number) row[15]).intValue() != 0;
            if (!deleted) changes.recordInserted(id);
            else changes.markUnchanged(id);
        }
    }

    private static final String SEARCH_FP_MODEL = "mhl.lucene.searchable-metadata";
    private static final int SEARCH_FP_VERSION = 1;

    static record ExistingSearchState(boolean exists, boolean deleted, boolean local, String catalogFingerprint,
                                      String searchModel, Integer searchVersion, String searchFingerprint) { }

    private record BatchClassification(List<NormalizedBook> persistenceBooks,
                                       List<NormalizedBook> searchStateBooks,
                                       List<CatalogBookSnapshot> seenSnapshots) { }

    private BatchClassification classifySearchChanges(List<NormalizedBook> books, ImportChangeAccumulator changes,
                                                       boolean collectTracking) {
        if (books.isEmpty()) return new BatchClassification(List.of(), List.of(), List.of());
        // Duplicate bind values are harmless in SQL IN(...), and books are still processed
        // individually below. Avoid a HashSet-backed Stream.distinct() on every 5k batch.
        List<String> ids = new ArrayList<>(books.size());
        for (NormalizedBook book : books) ids.add((String) book.row()[0]);
        Map<String, ExistingSearchState> existing = loadExistingSearchStates(ids);
        List<NormalizedBook> persistenceBooks = new ArrayList<>();
        List<NormalizedBook> searchStateBooks = new ArrayList<>();
        List<CatalogBookSnapshot> seenSnapshots = collectTracking ? new ArrayList<>(books.size()) : List.of();
        for (NormalizedBook book : books) {
            Object[] row = book.row();
            String id = (String) row[0];
            boolean incomingDeleted = ((Number) row[15]).intValue() != 0;
            boolean incomingLocal = ((Number) row[16]).intValue() != 0;
            ExistingSearchState old = existing.get(id);
            classifySearchChange(id, incomingDeleted, book.searchFingerprint(),
                    book.catalogSnapshot().catalogFingerprint(), old, changes);
            if (requiresBookPersistence(book.catalogSnapshot().catalogFingerprint(), incomingLocal, old)) {
                persistenceBooks.add(book);
            } else if (collectTracking) {
                seenSnapshots.add(book.catalogSnapshot());
            }
            if (requiresSearchStatePersistence(incomingDeleted, book.searchFingerprint(),
                    book.catalogSnapshot().catalogFingerprint(), old)) {
                searchStateBooks.add(book);
            }
        }
        return new BatchClassification(List.copyOf(persistenceBooks), List.copyOf(searchStateBooks),
                collectTracking ? List.copyOf(seenSnapshots) : List.of());
    }

    private Map<String, ExistingSearchState> loadExistingSearchStates(List<String> ids) {
        if (ids == null || ids.isEmpty()) return Map.of();
        int expected = Math.max(16, (int) Math.ceil(ids.size() / 0.75d));
        Map<String, ExistingSearchState> existing = new HashMap<>(expected);
        final int chunk = 400;
        for (int from = 0; from < ids.size(); from += chunk) {
            List<String> part = ids.subList(from, Math.min(ids.size(), from + chunk));
            String placeholders = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            getJdbcTemplate().query("""
                    SELECT b.id, COALESCE(b.deleted, 0) AS deleted, COALESCE(b.local, 0) AS local,
                           c.catalog_fingerprint, s.fingerprint_model, s.fingerprint_version, s.fingerprint
                      FROM books b
                      LEFT JOIN catalog_book_state c ON c.book_id=b.id
                      LEFT JOIN book_search_state s ON s.book_id=b.id
                     WHERE b.id IN (""" + placeholders + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        existing.put(rs.getString("id"), new ExistingSearchState(
                                true, rs.getInt("deleted") != 0, rs.getInt("local") != 0,
                                rs.getString("catalog_fingerprint"), rs.getString("fingerprint_model"),
                                (Integer) rs.getObject("fingerprint_version"), rs.getString("fingerprint")));
                    },
                    part.toArray());
        }
        return existing;
    }

    static boolean requiresBookPersistence(String incomingCatalogFingerprint, boolean incomingLocal,
                                           ExistingSearchState old) {
        if (old == null) return true;
        if (!Objects.equals(old.catalogFingerprint(), incomingCatalogFingerprint)) return true;
        // Preserve existing local bytes when the catalog says remote; only a newly discovered local file
        // requires a write when catalog metadata itself is unchanged.
        return incomingLocal && !old.local();
    }

    static boolean shouldComputePreviewSearchFingerprint(boolean incomingDeleted,
                                                         String incomingCatalogFingerprint,
                                                         ExistingSearchState old) {
        if (incomingDeleted) return false;
        if (old == null || old.deleted()) return true;
        if (!Objects.equals(old.catalogFingerprint(), incomingCatalogFingerprint)) return true;
        if (old.searchFingerprint() == null) return false;
        return !SEARCH_FP_MODEL.equals(old.searchModel())
                || old.searchVersion() == null
                || old.searchVersion() != SEARCH_FP_VERSION;
    }

    static boolean requiresSearchStatePersistence(boolean incomingDeleted, String incomingSearchFingerprint,
                                                  String incomingCatalogFingerprint, ExistingSearchState old) {
        if (incomingDeleted) return false;
        if (old == null || old.deleted()) return true;
        boolean sameSearch = SEARCH_FP_MODEL.equals(old.searchModel())
                && old.searchVersion() != null && old.searchVersion() == SEARCH_FP_VERSION
                && Objects.equals(old.searchFingerprint(), incomingSearchFingerprint);
        if (sameSearch) return false;
        // Initial baselines intentionally omit book_search_state. If the complete catalog fingerprint
        // is unchanged, the legacy fallback remains a valid proof that searchable metadata is unchanged.
        return !(old.searchFingerprint() == null
                && old.catalogFingerprint() != null
                && Objects.equals(old.catalogFingerprint(), incomingCatalogFingerprint));
    }

    static void classifySearchChange(String id, boolean incomingDeleted, String incomingSearchFingerprint,
                                     String incomingCatalogFingerprint, ExistingSearchState old,
                                     ImportChangeAccumulator changes) {
        if (incomingDeleted) {
            // A tombstone is a search change only when an actually indexed/active book becomes deleted.
            // Replaying an already-deleted row must not consume bounded change-tracking capacity.
            if (old != null && !old.deleted()) changes.recordDeleted(id);
            else changes.markUnchanged(id);
            return;
        }
        if (old == null || old.deleted()) {
            // New and re-activated books both need to be present in Lucene after the update.
            changes.recordInserted(id);
            return;
        }
        boolean sameSearch = SEARCH_FP_MODEL.equals(old.searchModel())
                && old.searchVersion() != null && old.searchVersion() == SEARCH_FP_VERSION
                && Objects.equals(old.searchFingerprint(), incomingSearchFingerprint);
        // Upgrade fallback: an unchanged v7 catalog fingerprint proves searchable metadata did not change.
        boolean sameLegacyCatalog = old.searchFingerprint() == null
                && old.catalogFingerprint() != null
                && Objects.equals(old.catalogFingerprint(), incomingCatalogFingerprint);
        if (!sameSearch && !sameLegacyCatalog && !changes.containsInserted(id)) changes.recordUpdated(id);
        else changes.markUnchanged(id);
    }

    private record SearchFingerprintRow(String bookId, String fingerprint) { }

    private void persistSearchFingerprints(List<NormalizedBook> books) {
        if (books.isEmpty()) return;
        List<SearchFingerprintRow> rows = new ArrayList<>(books.size());
        for (NormalizedBook book : books) {
            rows.add(new SearchFingerprintRow((String) book.row()[0], book.searchFingerprint()));
        }
        persistSearchFingerprintRows(rows);
    }

    private void persistSearchFingerprintRows(List<SearchFingerprintRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        getJdbcTemplate().batchUpdate("""
                INSERT INTO book_search_state(book_id, fingerprint_model, fingerprint_version, fingerprint, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(book_id) DO UPDATE SET
                    fingerprint_model=excluded.fingerprint_model,
                    fingerprint_version=excluded.fingerprint_version,
                    fingerprint=excluded.fingerprint,
                    updated_at=CURRENT_TIMESTAMP
                """, rows, 1000, (ps, row) -> {
            ps.setString(1, row.bookId());
            ps.setString(2, SEARCH_FP_MODEL);
            ps.setInt(3, SEARCH_FP_VERSION);
            ps.setString(4, row.fingerprint());
        });
    }

    private Map<String, String> flushPendingEntities(
            Map<AuthorNameKey, Author> pendingAuthors,
            Map<String, Genre> pendingGenres,
            boolean assumeNewAuthors) {
        Map<String, String> resolution = new HashMap<>();
        if (!pendingAuthors.isEmpty()) {
            List<Map.Entry<AuthorNameKey, Author>> entries = new ArrayList<>(pendingAuthors.entrySet());
            List<Author> candidates = entries.stream().map(Map.Entry::getValue).toList();
            Map<String, String> resolved = assumeNewAuthors
                    ? batchWriter.batchInsertAuthorsAndResolveIdsAssumingNew(candidates)
                    : batchWriter.batchInsertAuthorsAndResolveIds(candidates);
            for (Map.Entry<AuthorNameKey, Author> entry : entries) {
                String candidateId = entry.getValue().getId().asString();
                String persistentId = resolved.get(candidateId);
                if (persistentId == null || persistentId.isBlank()) {
                    throw new IllegalStateException("Author batch did not resolve candidate ID: " + candidateId);
                }
                authorCache.put(entry.getKey(), persistentId);
                resolution.put(candidateId, persistentId);
            }
            pendingAuthors.clear();
        }
        if (!pendingGenres.isEmpty()) {
            List<Genre> list = new ArrayList<>(pendingGenres.values());
            batchWriter.batchInsertGenres(list);
            for (Genre g : list) {
                genreCache.put(g.getId().asString(), g.getId().asString());
            }
            pendingGenres.clear();
        }
        return resolution;
    }

    private static boolean shouldPreloadAuthorCache(boolean onlineCollection, boolean catalogFullSnapshot, long totalRecords) {
        return onlineCollection && catalogFullSnapshot && totalRecords >= AUTHOR_PRELOAD_MIN_RECORDS;
    }

    private void preloadAuthorCacheIfFits(boolean authorTableInitiallyEmpty) {
        if (authorTableInitiallyEmpty || authorCache == null || authorCache.isEmpty() == false) return;
        int limit = normalizedAuthorCacheLimit(authorCacheSize);
        try {
            Long authorCount = getJdbcTemplate().queryForObject("SELECT COUNT(*) FROM authors", Long.class);
            if (authorCount == null || authorCount <= 0 || authorCount > limit) {
                if (authorCount != null && authorCount > limit) {
                    log.info("Author preload skipped: {} rows exceed cache limit {}", authorCount, limit);
                }
                return;
            }
            long started = System.nanoTime();
            getJdbcTemplate().query(
                    "SELECT id, first_name, middle_name, last_name FROM authors",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        String id = rs.getString("id");
                        if (id == null || id.isBlank()) return;
                        authorCache.put(new AuthorNameKey(
                                rs.getString("first_name"),
                                rs.getString("middle_name"),
                                rs.getString("last_name")), id);
                    });
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            log.info("Preloaded {} persistent authors into import cache in {} ms", authorCache.size(), elapsedMs);
        } catch (RuntimeException preloadFailure) {
            // Preload is an optimization only. Preserve the existing exact DB lookup fallback on any failure.
            authorCache.clear();
            log.warn("Author cache preload failed; continuing with incremental DB resolution", preloadFailure);
        }
    }

    private static int normalizedAuthorCacheLimit(int configuredLimit) {
        return Math.max(10_000, Math.min(configuredLimit, 500_000));
    }

    private static Map<AuthorNameKey, String> newBoundedAuthorCache(int configuredLimit) {
        int limit = normalizedAuthorCacheLimit(configuredLimit);
        int initialCapacity = Math.min(65_536, Math.max(16_384, limit / 4));
        return new BoundedAuthorCache(limit, initialCapacity);
    }

    private boolean authorCacheHasEvicted() {
        return authorCache instanceof BoundedAuthorCache bounded && bounded.hasEvicted();
    }

    private static final class BoundedAuthorCache extends LinkedHashMap<AuthorNameKey, String> {
        private final int limit;
        private boolean evicted;

        private BoundedAuthorCache(int limit, int initialCapacity) {
            super(initialCapacity, 0.75f, true);
            this.limit = limit;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<AuthorNameKey, String> eldest) {
            boolean remove = size() > limit;
            if (remove) evicted = true;
            return remove;
        }

        private boolean hasEvicted() {
            return evicted;
        }
    }

    private Map<String, String> buildGenreCache() {
        Map<String, String> cache = new HashMap<>();
        getJdbcTemplate().query("SELECT code FROM genres",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String code = rs.getString(1);
                    if (code != null && !code.isBlank()) cache.put(code, code);
                });
        return cache;
    }

    private boolean isBookCatalogEmpty() {
        if (!collectionManager.hasActiveCollection()) return false;
        try {
            Integer exists = getJdbcTemplate().queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM books LIMIT 1)", Integer.class);
            return exists == null || exists == 0;
        } catch (RuntimeException e) {
            log.debug("Cannot determine whether catalog is empty; using conservative import path", e);
            return false;
        }
    }

    private boolean isAuthorCatalogEmpty() {
        try {
            Integer exists = getJdbcTemplate().queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM authors LIMIT 1)", Integer.class);
            return exists == null || exists == 0;
        } catch (RuntimeException e) {
            log.debug("Cannot determine whether author table is empty; disabling fast author insert", e);
            return false;
        }
    }

    /**
     * Online catalogs derive one candidate archive path per book. On Windows, probing hundreds of
     * thousands of non-existent paths dominates import time. Scan the local root once and turn the
     * per-record check into an O(1) hash lookup. Files.walk does not follow directory symlinks.
     */
    private static Set<Path> indexExistingLocalFiles(Path root, AtomicBoolean cancelFlag) {
        if (root == null || !Files.isDirectory(root)) return Set.of();
        Set<Path> result = new HashSet<>();
        try (var paths = Files.walk(root)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()) {
                if (isCancelled(cancelFlag)) return Set.of();
                Path path = iterator.next();
                if (Files.isRegularFile(path)) result.add(path.toAbsolutePath().normalize());
            }
            return Collections.unmodifiableSet(result);
        } catch (IOException | RuntimeException e) {
            log.warn("Cannot pre-index local online-library files under {}; falling back to filesystem probes", root, e);
            return null;
        }
    }

    private static boolean isCancelled(AtomicBoolean cancelFlag) {
        return cancelFlag != null && cancelFlag.get();
    }

    private static void notifyProgress(DoubleConsumer listener, double value) {
        if (listener != null) {
            listener.accept(Math.max(0.0, Math.min(1.0, value)));
        }
    }

    private static void logPoolState(javax.sql.DataSource dataSource, String phase) {
        if (!(dataSource instanceof HikariDataSource hikari)) return;
        try {
            var pool = hikari.getHikariPoolMXBean();
            log.info(
                    "INPX Hikari [{}]: pool={} max={} minIdle={} connectionTimeout={}ms leakDetection={}ms active={} idle={} total={} waiting={}",
                    phase, hikari.getPoolName(), hikari.getMaximumPoolSize(), hikari.getMinimumIdle(),
                    hikari.getConnectionTimeout(), hikari.getLeakDetectionThreshold(),
                    pool == null ? -1 : pool.getActiveConnections(),
                    pool == null ? -1 : pool.getIdleConnections(),
                    pool == null ? -1 : pool.getTotalConnections(),
                    pool == null ? -1 : pool.getThreadsAwaitingConnection());
        } catch (RuntimeException metricsFailure) {
            log.debug("Cannot read Hikari metrics during {}", phase, metricsFailure);
        }
    }

    private static void notifyStatus(Consumer<String> consumer, String status) {
        if (consumer != null) {
            consumer.accept(status);
        }
    }

    private static void reportProgress(
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer,
            Consumer<OperationProgress> operationProgressListener,
            String operationId,
            long processed,
            long total,
            long inserted,
            long updated,
            long deleted,
            long skipped,
            long duplicates,
            long errors) {
        double progress = total <= 0 ? 1.0 : (double) processed / (double) total;
        notifyProgress(progressListener, progress);
        notifyOperation(operationProgressListener, new OperationProgress(operationId, OperationStage.IMPORTING,
                processed, total, 0, -1, inserted, updated, deleted, skipped, duplicates, 0, errors, "", true));
        notifyStatus(statusConsumer, String.format(
                Locale.ROOT,
                "Імпорт INPX: %,d / %,d записів · +%,d ~%,d -%,d · помилок %,d",
                processed, total, inserted, updated, deleted, errors));
    }

    private static void notifyOperation(Consumer<OperationProgress> listener, OperationProgress progress) {
        if (listener == null || progress == null || progress.operationId() == null) return;
        try { listener.accept(progress); }
        catch (RuntimeException callbackFailure) { log.debug("Operation progress callback failed", callbackFailure); }
    }

    private static String sha256(Path file, AtomicBoolean cancelFlag) {
        try {
            return Sha256Support.file(file, () -> cancelFlag != null && cancelFlag.get()).orElse(null);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint INPX: " + file, e);
        }
    }




}
