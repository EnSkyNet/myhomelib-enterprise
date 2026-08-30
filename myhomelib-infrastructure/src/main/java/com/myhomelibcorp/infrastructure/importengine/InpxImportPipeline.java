package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.shared.util.Sha256Support;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

@Component
@RequiredArgsConstructor
@Slf4j
public class InpxImportPipeline {
    private final InpxReader reader;
    private final JdbcBatchWriter batchWriter;
    private final BulkImportOptimizer bulkOptimizer;
    private final CollectionManager collectionManager;
    private final CatalogUpdateTrackingPort catalogUpdateTrackingPort;
    private final ImportIndexLifecycle importIndexLifecycle;

    @Value("${app.import.change-tracking-limit:50000}")
    private int changeTrackingLimit;

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
        int effectiveBatch = Math.max(50, Math.min(batchSize <= 0 ? 1000 : batchSize, 10_000));
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

        log.info("Starting INPX import: {} (root: {}, batch: {}, sourceKey: {}, records: {})",
                file, root, effectiveBatch, sourceKey, totalRecords);

        // Important: never preload SELECT * FROM authors. The import cache starts empty and
        // is populated incrementally from actual persistent IDs on each flush.
        this.authorCache = newBoundedAuthorCache();
        this.genreCache = buildGenreCache();

        boolean optimized = false;
        ImportOutcome outcome;
        try {
            bulkOptimizer.enableBulkInsertMode();
            optimized = true;

            var dataSource = collectionManager.getCurrentDataSource();
            if (dataSource != null) {
                TransactionTemplate transaction =
                        new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                outcome = transaction.execute(status -> {
                    ImportOutcome current = importTransactional(
                            file, effectiveBatch, root, cancelFlag, sourceKey, sourceLocation,
                            sourceFingerprint, totalRecords, catalogFullSnapshot, onlineCollection, progressListener, statusConsumer,
                            opId, operationProgressListener);
                    if (current.cancelled()) {
                        status.setRollbackOnly();
                        log.info("INPX import cancelled; transaction rolled back after {} parsed records",
                                current.processed());
                    }
                    return current;
                });
                if (outcome == null) {
                    outcome = new ImportOutcome(0, 0, 0, 0, 0, false, ImportChangeSet.empty(!catalogFullSnapshot));
                }
            } else {
                outcome = importTransactional(
                        file, effectiveBatch, root, cancelFlag, sourceKey, sourceLocation,
                        sourceFingerprint, totalRecords, catalogFullSnapshot, onlineCollection, progressListener, statusConsumer,
                        opId, operationProgressListener);
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
                    ImportStatus.CANCELLED, ImportChangeSet.empty(false), List.of());
        }

        notifyProgress(progressListener, 1.0);
        notifyOperation(operationProgressListener, new OperationProgress(opId, OperationStage.IMPORTING,
                outcome.processed(), totalRecords, 0, -1, outcome.changes().insertedCount(),
                outcome.changes().updatedCount(), outcome.changes().deletedCount(), outcome.skipped(),
                outcome.duplicates(), 0, outcome.errors(), "", true));
        notifyStatus(statusConsumer, String.format(Locale.ROOT,
                "Імпорт INPX завершено: %,d / %,d записів", outcome.imported(), totalRecords));
        log.info("INPX import completed: imported={}, skipped={}, duplicates={}, errors={}, durationMs={}",
                outcome.imported(), outcome.skipped(), outcome.duplicates(), outcome.errors(), duration);
        return new ImportResult(
                outcome.imported(), outcome.skipped(), outcome.duplicates(), outcome.errors(), duration,
                outcome.errors() > 0 ? ImportStatus.SUCCESS_WITH_WARNINGS : ImportStatus.SUCCESS,
                outcome.changes(), List.of());
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
            DoubleConsumer progressListener,
            Consumer<String> statusConsumer,
            String operationId,
            Consumer<OperationProgress> operationProgressListener) {

        boolean tracked = collectionManager.hasActiveCollection();
        CatalogSyncSession syncSession = tracked
                ? catalogUpdateTrackingPort.beginSync(sourceKey, sourceLocation, sourceFingerprint)
                : new CatalogSyncSession(
                        CatalogSourceIdentity.stableId(sourceKey), sourceKey, 1L, sourceFingerprint, true, true);
        if (tracked && catalogFullSnapshot) {
            catalogUpdateTrackingPort.markTrackedBooksMissing(syncSession);
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
        long processed = 0;
        long lastReported = 0;
        ImportIndexLifecycle.SuspendedIndexes suspendedIndexes = ImportIndexLifecycle.SuspendedIndexes.empty();
        boolean cancelled = false;
        ImportChangeAccumulator changes = new ImportChangeAccumulator(effectiveChangeTrackingLimit());

        notifyOperation(operationProgressListener, new OperationProgress(operationId, OperationStage.IMPORTING,
                0, totalRecords, 0, -1, 0, 0, 0, 0, 0, 0, 0, "", true));

        try {
            if (catalogFullSnapshot) {
                suspendedIndexes = importIndexLifecycle.suspendForFullSnapshot();
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
                            raw, pendingAuthors, pendingGenres, root, localCache, sourceMarker);
                    if (row == null) {
                        errors++;
                    } else {
                        books.add(row);
                    }

                    if (books.size() >= effectiveBatch) {
                        int batchCount = books.size();
                        flush(books, pendingAuthors, pendingGenres, syncSession, tracked, changes);
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
                flush(books, pendingAuthors, pendingGenres, syncSession, tracked, changes);
                imported += batchCount;
                books.clear();
            }

            if (!cancelled && processed != lastReported) {
                reportProgress(progressListener, statusConsumer, operationProgressListener, operationId,
                        processed, totalRecords, changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                        skipped, duplicates, errors);
            }

            if (catalogFullSnapshot && tracked) {
                getJdbcTemplate().query(
                        "SELECT b.id FROM books b JOIN catalog_book_state c ON c.book_id=b.id " +
                                "WHERE c.source_id=? AND COALESCE(b.deleted,0)<>0",
                        (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                                changes.recordDeleted(rs.getString(1)),
                        syncSession.sourceId());
            }
            ImportChangeSet changeSet = changes.snapshot();
            return new ImportOutcome(imported, skipped, duplicates, errors, processed, cancelled, changeSet);
        } finally {
            importIndexLifecycle.restore(suspendedIndexes);
        }
    }

    private record ImportOutcome(
            long imported,
            long skipped,
            long duplicates,
            long errors,
            long processed,
            boolean cancelled,
            ImportChangeSet changes) {
    }

    public long importFile(Path file, int batchSize) {
        return importFile(file, batchSize, null, null, null, null);
    }

    private void flush(
            List<NormalizedBook> books,
            Map<AuthorNameKey, Author> pendingAuthors,
            Map<String, Genre> pendingGenres,
            CatalogSyncSession syncSession,
            boolean tracked,
            ImportChangeAccumulator changes) {
        Map<String, String> authorResolution = flushPendingEntities(pendingAuthors, pendingGenres);
        List<Object[]> rows = books.stream().map(NormalizedBook::row).toList();
        classifySearchChanges(books, changes);
        batchWriter.batchInsertFull(rows, authorResolution);
        persistSearchFingerprints(books);
        if (tracked) {
            catalogUpdateTrackingPort.recordImportedBooks(
                    syncSession, books.stream().map(NormalizedBook::catalogSnapshot).toList());
        }
    }

    private static final String SEARCH_FP_MODEL = "mhl.lucene.searchable-metadata";
    private static final int SEARCH_FP_VERSION = 1;

    private record ExistingSearchState(boolean exists, String catalogFingerprint,
                                       String searchModel, Integer searchVersion, String searchFingerprint) { }

    private void classifySearchChanges(List<NormalizedBook> books, ImportChangeAccumulator changes) {
        if (books.isEmpty()) return;
        List<String> ids = books.stream().map(b -> (String) b.row()[0]).distinct().toList();
        Map<String, ExistingSearchState> existing = new HashMap<>();
        final int chunk = 400;
        for (int from = 0; from < ids.size(); from += chunk) {
            List<String> part = ids.subList(from, Math.min(ids.size(), from + chunk));
            String placeholders = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            getJdbcTemplate().query("""
                    SELECT b.id, c.catalog_fingerprint, s.fingerprint_model, s.fingerprint_version, s.fingerprint
                      FROM books b
                      LEFT JOIN catalog_book_state c ON c.book_id=b.id
                      LEFT JOIN book_search_state s ON s.book_id=b.id
                     WHERE b.id IN (""" + placeholders + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        existing.put(rs.getString("id"), new ExistingSearchState(
                                true, rs.getString("catalog_fingerprint"), rs.getString("fingerprint_model"),
                                (Integer) rs.getObject("fingerprint_version"), rs.getString("fingerprint")));
                    },
                    part.toArray());
        }
        for (NormalizedBook book : books) {
            Object[] row = book.row();
            String id = (String) row[0];
            boolean incomingDeleted = ((Number) row[15]).intValue() != 0;
            ExistingSearchState old = existing.get(id);
            if (incomingDeleted) {
                if (old != null) changes.recordDeleted(id);
                else changes.markUnchanged(id);
                continue;
            }
            if (old == null) {
                changes.recordInserted(id);
                continue;
            }
            boolean sameSearch = SEARCH_FP_MODEL.equals(old.searchModel())
                    && old.searchVersion() != null && old.searchVersion() == SEARCH_FP_VERSION
                    && Objects.equals(old.searchFingerprint(), book.searchFingerprint());
            // Upgrade fallback: an unchanged v7 catalog fingerprint proves searchable metadata did not change.
            boolean sameLegacyCatalog = old.searchFingerprint() == null
                    && old.catalogFingerprint() != null
                    && Objects.equals(old.catalogFingerprint(), book.catalogSnapshot().catalogFingerprint());
            if (!sameSearch && !sameLegacyCatalog && !changes.containsInserted(id)) changes.recordUpdated(id);
            else changes.markUnchanged(id);
        }
    }

    private void persistSearchFingerprints(List<NormalizedBook> books) {
        if (books.isEmpty()) return;
        getJdbcTemplate().batchUpdate("""
                INSERT INTO book_search_state(book_id, fingerprint_model, fingerprint_version, fingerprint, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT(book_id) DO UPDATE SET
                    fingerprint_model=excluded.fingerprint_model,
                    fingerprint_version=excluded.fingerprint_version,
                    fingerprint=excluded.fingerprint,
                    updated_at=CURRENT_TIMESTAMP
                """, books, 1000, (ps, book) -> {
            ps.setString(1, (String) book.row()[0]);
            ps.setString(2, SEARCH_FP_MODEL);
            ps.setInt(3, SEARCH_FP_VERSION);
            ps.setString(4, book.searchFingerprint());
        });
    }

    private Map<String, String> flushPendingEntities(
            Map<AuthorNameKey, Author> pendingAuthors,
            Map<String, Genre> pendingGenres) {
        Map<String, String> resolution = new HashMap<>();
        if (!pendingAuthors.isEmpty()) {
            List<Map.Entry<AuthorNameKey, Author>> entries = new ArrayList<>(pendingAuthors.entrySet());
            Map<String, String> resolved = batchWriter.batchInsertAuthorsAndResolveIds(
                    entries.stream().map(Map.Entry::getValue).toList());
            for (Map.Entry<AuthorNameKey, Author> entry : entries) {
                String candidateId = entry.getValue().getId().asString();
                String persistentId = resolved.getOrDefault(candidateId, candidateId);
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

    private static Map<AuthorNameKey, String> newBoundedAuthorCache() {
        return new LinkedHashMap<>(16_384, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<AuthorNameKey, String> eldest) {
                return size() > 50_000;
            }
        };
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

    private static boolean isCancelled(AtomicBoolean cancelFlag) {
        return cancelFlag != null && cancelFlag.get();
    }

    private static void notifyProgress(DoubleConsumer listener, double value) {
        if (listener != null) {
            listener.accept(Math.max(0.0, Math.min(1.0, value)));
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
