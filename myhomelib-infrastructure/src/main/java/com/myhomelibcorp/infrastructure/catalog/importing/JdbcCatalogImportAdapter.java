package com.myhomelibcorp.infrastructure.catalog.importing;

import com.myhomelibcorp.application.catalog.CatalogSourceIdentity;
import com.myhomelibcorp.application.catalog.importing.CatalogReadSession;
import com.myhomelibcorp.application.catalog.importing.CatalogDatasetInfo;
import com.myhomelibcorp.application.catalog.importing.CatalogReader;
import com.myhomelibcorp.application.catalog.importing.CatalogRecord;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.diagnostics.ImportIssue;
import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.port.out.catalog.CatalogImportPort;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Streaming persistence adapter for source-neutral catalog readers.
 *
 * <p>The adapter intentionally keeps only one batch in memory. Full snapshots use a temporary
 * SQLite table to track seen book IDs, so 500k-1M records do not become a giant Java collection.
 * User-owned fields (rating/progress/review/local file state/created_at) are preserved on UPSERT.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JdbcCatalogImportAdapter implements CatalogImportPort {
    private static final int DEFAULT_BATCH = 1_000;
    private static final int SQLITE_QUERY_CHUNK = 400;
    private static final int MAX_ISSUES = 1_000;

    private final List<CatalogReader> readers;
    private final CollectionManager collectionManager;
    private final CatalogImportManifestStore manifestStore = new CatalogImportManifestStore();
    private final JdbcCatalogBatchWriter batchWriter = new JdbcCatalogBatchWriter();

    @Value("${app.import.change-tracking-limit:50000}")
    private int changeTrackingLimit;

    private int effectiveChangeTrackingLimit() {
        return ImportChangeAccumulator.normalizeLimit(changeTrackingLimit);
    }

    @Override
    public boolean supports(Path source) {
        if (source == null || !Files.isRegularFile(source)) return false;
        for (CatalogReader reader : readers) {
            try {
                if (reader.supports(source)) return true;
            } catch (RuntimeException ignored) {
                // A supports() probe must never make generic file import unusable.
            }
        }
        return false;
    }

    @Override
    public ImportResult importCatalog(ImportContext context) {
        if (context == null || context.getFile() == null) {
            throw new IllegalArgumentException("Catalog source is required");
        }
        if (!collectionManager.hasActiveCollection()) {
            throw new IllegalStateException("Catalog import requires an active collection");
        }

        Path source = context.getFile().toAbsolutePath().normalize();
        CatalogReader reader = requireReader(source);
        String sourceKey = manifestStore.sourceKey(context, source);
        String sourceId = CatalogSourceIdentity.stableId(sourceKey);
        long started = System.currentTimeMillis();
        CatalogImportManifestStore.SourceStat sourceStat = manifestStore.stat(source);
        long size = sourceStat.sizeBytes();
        long mtime = sourceStat.mtimeMillis();

        JdbcTemplate jdbc = collectionManager.getCurrentJdbcTemplate();
        String sourceFormat = reader.formatName();
        CatalogImportManifestStore.Manifest manifest = manifestStore.find(jdbc, sourceKey);
        boolean compatibleManifest = manifestStore.isCompatible(manifest, source, sourceFormat);
        if (compatibleManifest && manifest.sizeBytes() == size && manifest.mtimeMillis() == mtime) {
            log.info("Catalog source unchanged; reusing compatible v7.1 manifest for {}", source);
            return new ImportResult(0, Math.max(0, manifest.recordCount()), 0, 0,
                    System.currentTimeMillis() - started, ImportStatus.SUCCESS,
                    ImportChangeSet.empty(true), List.of());
        }

        // Strong checksum is intentionally deferred until metadata changes or compatibility is uncertain.
        String fingerprint = manifestStore.fingerprint(source, context.getCancelFlag());
        if (isCancelled(context.getCancelFlag())) return ImportResult.cancelled(System.currentTimeMillis() - started);
        if (compatibleManifest && fingerprint.equals(manifest.fingerprint())) {
            manifestStore.touch(jdbc, sourceKey, source, sourceStat, fingerprint, manifest.recordCount(),
                    manifest.datasetSchema(), sourceFormat, manifest.datasetNormalizationModel());
            return new ImportResult(0, Math.max(0, manifest.recordCount()), 0, 0,
                    System.currentTimeMillis() - started, ImportStatus.SUCCESS,
                    ImportChangeSet.empty(true), List.of());
        }

        DataSource dataSource = collectionManager.getCurrentDataSource();
        if (dataSource == null) throw new IllegalStateException("Active collection has no datasource");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        try (CatalogReadSession session = reader.open(source)) {
            CatalogDatasetInfo datasetInfo = session.dataset();
            Consumer<String> status = context.getStatusConsumer();
            notifyStatus(status, "Імпорт каталогу: " + reader.formatName());

            ImportResult result = tx.execute(transactionStatus -> {
                try {
                    return importTransactional(jdbc, session, context, source, sourceKey, sourceId,
                            fingerprint, size, mtime, datasetInfo, sourceFormat, started);
                } catch (CancelledImportException e) {
                    transactionStatus.setRollbackOnly();
                    return ImportResult.cancelled(System.currentTimeMillis() - started);
                } catch (RuntimeException e) {
                    transactionStatus.setRollbackOnly();
                    throw e;
                }
            });
            if (result == null) throw new IllegalStateException("Catalog transaction produced no result");
            return result;
        }
    }

    private ImportResult importTransactional(
            JdbcTemplate jdbc,
            CatalogReadSession session,
            ImportContext context,
            Path source,
            String sourceKey,
            String sourceId,
            String sourceFingerprint,
            long sourceSize,
            long sourceMtime,
            CatalogDatasetInfo datasetInfo,
            String sourceFormat,
            long started) {

        String datasetSchema = datasetInfo == null ? "" : datasetInfo.schema();
        manifestStore.ensureSourceRow(jdbc, sourceId, sourceKey, context.getCatalogSourceLocation(), sourceFingerprint);
        batchWriter.persistDatasetMetadata(jdbc, sourceId, datasetInfo);
        boolean fullSnapshot = context.isCatalogFullSnapshot();
        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : DEFAULT_BATCH;
        List<CatalogRecord> batch = new ArrayList<>(batchSize);
        List<ImportIssue> issues = new ArrayList<>();
        ImportChangeAccumulator changes = fullSnapshot ? null
                : new ImportChangeAccumulator(effectiveChangeTrackingLimit());
        long processed = 0;
        long imported = 0;
        long errors = 0;

        if (fullSnapshot) {
            jdbc.execute("CREATE TEMP TABLE IF NOT EXISTS temp_catalog_seen_v7(book_id TEXT PRIMARY KEY)");
            jdbc.update("DELETE FROM temp_catalog_seen_v7");
        }

        Long declared = session.dataset().declaredRecords();
        while (session.hasNext()) {
            if (isCancelled(context.getCancelFlag())) throw new CancelledImportException();
            CatalogRecord record = session.next();
            processed++;
            addIssues(issues, record.issues());
            if (record.sourceBookId().isBlank()) {
                errors++;
                addIssue(issues, new ImportIssue(null, "validate", "MISSING_SOURCE_BOOK_ID",
                        Long.toString(processed), "Record has no stable source book id", false, Map.of()));
                continue;
            }
            batch.add(record);
            if (batch.size() >= batchSize) {
                batchWriter.persistBatch(jdbc, batch, sourceId, sourceKey, context, fullSnapshot, changes);
                imported += batch.size();
                batch.clear();
                report(context.getProgressListener(), context.getStatusConsumer(), processed, declared);
            }
        }
        if (!batch.isEmpty()) {
            batchWriter.persistBatch(jdbc, batch, sourceId, sourceKey, context, fullSnapshot, changes);
            imported += batch.size();
            batch.clear();
        }

        if (fullSnapshot) {
            // Only books owned by this source are affected. Absence from a delta never implies deletion.
            jdbc.update("""
                    UPDATE books SET deleted = 1
                    WHERE id IN (
                        SELECT bi.book_id FROM book_identities bi
                        WHERE bi.source_id = ? AND bi.scheme = 'catalog-record'
                          AND NOT EXISTS (SELECT 1 FROM temp_catalog_seen_v7 s WHERE s.book_id = bi.book_id)
                    )
                    """, sourceId);
        }

        manifestStore.touch(jdbc, sourceKey, source,
                new CatalogImportManifestStore.SourceStat(sourceSize, sourceMtime), sourceFingerprint,
                processed, datasetSchema, sourceFormat, datasetMetadata(datasetInfo, "normalization.model"));
        jdbc.update("""
                UPDATE catalog_sources
                   SET source_fingerprint = ?, last_synced_at = CURRENT_TIMESTAMP,
                       dataset_schema = COALESCE(NULLIF(?, ''), dataset_schema)
                 WHERE source_key = ?
                """, sourceFingerprint, datasetSchema, sourceKey);

        ImportChangeSet changeSet = fullSnapshot
                ? ImportChangeSet.empty(false)
                : changes.snapshot();
        ImportStatus resultStatus = errors > 0 || !issues.isEmpty()
                ? ImportStatus.SUCCESS_WITH_WARNINGS : ImportStatus.SUCCESS;
        report(context.getProgressListener(), context.getStatusConsumer(), processed, declared);
        return new ImportResult(imported, 0, 0, errors, System.currentTimeMillis() - started,
                resultStatus, changeSet, issues);
    }

    private CatalogReader requireReader(Path source) {
        return readers.stream().filter(r -> {
            try { return r.supports(source); } catch (RuntimeException e) { return false; }
        }).findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported catalog format: " + source));
    }

    private static void report(DoubleConsumer progress, Consumer<String> status, long processed, Long declared) {
        if (progress != null && declared != null && declared > 0) progress.accept(Math.min(1.0, processed / (double) declared));
        if (status != null && (processed == 1 || processed % 10_000 == 0 || (declared != null && processed == declared))) {
            status.accept(declared != null && declared > 0
                    ? String.format(Locale.ROOT, "Імпорт каталогу: %,d / %,d", processed, declared)
                    : String.format(Locale.ROOT, "Імпорт каталогу: %,d", processed));
        }
    }
    private static void notifyStatus(Consumer<String> status, String text) { if (status != null) status.accept(text); }
    private static boolean isCancelled(AtomicBoolean cancel) { return cancel != null && cancel.get(); }

    private static String datasetMetadata(CatalogDatasetInfo dataset, String key) {
        if (dataset == null || dataset.metadata() == null || key == null) return "";
        String value = dataset.metadata().get(key);
        return value == null ? "" : value;
    }
    private static void addIssues(List<ImportIssue> out, List<ImportIssue> incoming) {
        if (incoming == null) return;
        for (ImportIssue issue : incoming) addIssue(out, issue);
    }
    private static void addIssue(List<ImportIssue> out, ImportIssue issue) {
        if (issue != null && out.size() < MAX_ISSUES) out.add(issue);
    }

    private static final class CancelledImportException extends RuntimeException { }
}
