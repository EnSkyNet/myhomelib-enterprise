package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.progress.OperationProgress;
import com.myhomelibcorp.application.progress.OperationStage;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.imports.error.ImportErrorHandler;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.imports.statistics.ImportStatistics;
import com.myhomelibcorp.application.port.out.catalog.CatalogImportPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.importer.FastImportService;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportFileUseCase {

    private final ImporterRegistry importerRegistry;
    private final BookSaver bookSaver;
    private final ImportErrorHandler errorHandler;
    private final EventPublisher eventPublisher;
    private final BulkImportOptimizer bulkImportOptimizer;
    private final FastImportService fastImportService;
    private final SearchIndexer searchIndexer;
    private final CatalogImportPort catalogImportPort;
    private final BookQueryRepository bookQueryRepository;
    private final LibraryOperationCoordinator operationCoordinator;

    @Value("${app.import.batch-size:1000}")
    private int defaultBatchSize;

    @Value("${app.import.change-tracking-limit:50000}")
    private int changeTrackingLimit;

    // ==================== ОСНОВНИЙ МЕТОД ====================

    public ImportResult execute(ImportContext context) {
        if (context == null || context.getFile() == null) {
            throw new IllegalArgumentException("File cannot be null");
        }

        try (var ignored = operationCoordinator.acquire(LibraryOperationType.IMPORT)) {
            String fileName = context.getFile().getFileName().toString().toLowerCase(Locale.ROOT);
            if (fileName.endsWith(".inpx") || fileName.endsWith(".inp")) {
                return executeInpx(context);
            }
            if (catalogImportPort.supports(context.getFile())) {
                return executeNeutralCatalog(context);
            }

            return executeLegacy(context);
        }
    }

    // ==================== ВНУТРІШНІ МЕТОДИ ====================

    private ImportResult executeInpx(ImportContext context) {
        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;
        Path rootDirectory = context.getRootDirectory();
        String operationId = operationId(context, "file-import-");
        Consumer<OperationProgress> telemetry = context.getOperationProgressListener();
        DoubleConsumer importProgress = scaleImportPhaseProgress(context.getProgressListener(), context.isIndexAfterSave());

        ImportResult result = fastImportService.importInpx(
                context.getFile(),
                batchSize,
                rootDirectory,
                context.getCancelFlag(),
                context.getCatalogSourceKey(),
                context.getCatalogSourceLocation(),
                context.isCatalogFullSnapshot(),
                importProgress,
                context.getStatusConsumer(),
                operationId,
                telemetry);

        if (result.status() == ImportStatus.CANCELLED) {
            publishFinished(context, result);
            return result;
        }

        if (context.isIndexAfterSave() && requiresSearchFinalization(result)) {
            emitOperation(telemetry, OperationProgress.stage(operationId, OperationStage.UPDATING_SEARCH_INDEX, true)
                    .withCounts(result.changes().insertedCount(), result.changes().updatedCount(), result.changes().deletedCount(),
                            result.skipped(), result.duplicates(), result.issues().size(), result.errors()));
            if (context.getStatusConsumer() != null) context.getStatusConsumer().accept("Оновлення пошукового індексу…");
            if (context.getProgressListener() != null) context.getProgressListener().accept(-1.0);
            if (result.changes().complete()) {
                applyIncrementalIndex(result.changes());
                log.info("📌 Selective Lucene update applied after fast INPX import: +{}, ~{}, -{}",
                        result.changes().insertedCount(), result.changes().updatedCount(), result.changes().deletedCount());
            } else {
                // Exact IDs were deliberately discarded after the bounded tracking threshold.
                // Rebuild from the committed database instead of pretending that commit() indexes DB rows.
                searchIndexer.rebuildIndex();
                log.info("📌 Full Lucene rebuild completed after bounded INPX change tracking overflow");
            }
        }

        if (context.getProgressListener() != null) context.getProgressListener().accept(1.0);
        if (context.isPublishFinishedEvent()) {
            long processed = processedForResult(result);
            emitOperation(telemetry, OperationProgress.stage(operationId, OperationStage.COMPLETED, false)
                    .withProgress(processed, processed)
                    .withCounts(result.changes().insertedCount(), result.changes().updatedCount(), result.changes().deletedCount(),
                            result.skipped(), result.duplicates(), result.issues().size(), result.errors()));
        }
        publishFinished(context, result);
        return result;
    }

    private static boolean requiresSearchFinalization(ImportResult result) {
        if (result == null || result.changes() == null) return false;
        var changes = result.changes();
        long trackedChanges = changes.insertedCount() + changes.updatedCount() + changes.deletedCount();
        // Incomplete means exact IDs are unavailable. Compatibility FastImportService implementations
        // may only report imported rows, so a positive import still requires a safe full rebuild.
        return trackedChanges > 0 || (!changes.complete() && result.imported() > 0);
    }

    private ImportResult executeNeutralCatalog(ImportContext context) {
        String operationId = operationId(context, "catalog-import-");
        Consumer<OperationProgress> telemetry = context.getOperationProgressListener();
        ImportResult result = catalogImportPort.importCatalog(context);
        if (result.status() == ImportStatus.CANCELLED) {
            emitOperation(telemetry, OperationProgress.stage(operationId, OperationStage.CANCELLED, false));
            publishFinished(context, result);
            return result;
        }

        if (result.imported() > 0 && context.isIndexAfterSave()) {
            emitOperation(telemetry, OperationProgress.stage(operationId, OperationStage.UPDATING_SEARCH_INDEX, true)
                    .withCounts(result.changes().insertedCount(), result.changes().updatedCount(), result.changes().deletedCount(),
                            result.skipped(), result.duplicates(), result.issues().size(), result.errors()));
            // Source-neutral full snapshots do not guarantee an exact ImportChangeSet at the port boundary.
            // The current JDBC adapter deliberately tracks snapshot membership in SQLite and returns
            // complete=false rather than retaining every changed ID in memory. Keep full snapshots on
            // the safe rebuild path until the CatalogImportPort contract itself guarantees exact IDs.
            if (context.isCatalogFullSnapshot() || !result.changes().complete()) {
                searchIndexer.rebuildIndex();
            } else {
                applyIncrementalIndex(result.changes());
            }
        }
        if (context.isPublishFinishedEvent()) {
            long processed = processedForResult(result);
            emitOperation(telemetry, OperationProgress.stage(operationId, OperationStage.COMPLETED, false)
                    .withProgress(processed, processed)
                    .withCounts(result.changes().insertedCount(), result.changes().updatedCount(), result.changes().deletedCount(),
                            result.skipped(), result.duplicates(), result.issues().size(), result.errors()));
        }
        publishFinished(context, result);
        return result;
    }

    private void applyIncrementalIndex(com.myhomelibcorp.application.imports.statistics.ImportChangeSet changes) {
        boolean begun = false;
        try {
            searchIndexer.beginAtomicUpdate();
            begun = true;
            for (String id : changes.deleted()) searchIndexer.deleteBook(BookId.fromString(id));

            Set<String> changed = new LinkedHashSet<>(changes.inserted());
            changed.addAll(changes.updated());
            List<String> ids = new ArrayList<>(changed);
            for (int from = 0; from < ids.size(); from += 400) {
                List<BookId> batchIds = ids.subList(from, Math.min(ids.size(), from + 400)).stream()
                        .map(BookId::fromString).toList();
                for (Book book : bookQueryRepository.findByIds(batchIds)) {
                    if (book == null) continue;
                    if (book.isDeleted()) searchIndexer.deleteBook(book.getId());
                    else searchIndexer.indexBook(book);
                }
            }
            searchIndexer.commit();
        } catch (RuntimeException e) {
            if (begun) {
                try { searchIndexer.rollbackAtomicUpdate(); } catch (RuntimeException rollback) { e.addSuppressed(rollback); }
            }
            throw e;
        }
    }

    private ImportResult executeLegacy(ImportContext context) {
        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;
        // Отримуємо progress listener з контексту
        DoubleConsumer progressListener = context.getProgressListener();
        DoubleConsumer importProgressListener = scaleImportPhaseProgress(progressListener, context.isIndexAfterSave());
        AtomicLong totalProcessed = new AtomicLong(0);
        AtomicLong lastReported = new AtomicLong(0);
        long startTime = System.currentTimeMillis();
        String operationId = operationId(context, "legacy-import-");
        Consumer<OperationProgress> telemetry = context.getOperationProgressListener();

        long estimatedCount = -1;
        try {
            var importer = importerRegistry.findImporter(context.getFile());
            estimatedCount = importer.countBooks(context.getFile());
        } catch (Exception e) {
            log.debug("Не вдалося оцінити кількість книг для progress telemetry");
        }

        ImportStatistics stats = new ImportStatistics();
        ImportChangeAccumulator changes = new ImportChangeAccumulator(
                ImportChangeAccumulator.normalizeLimit(changeTrackingLimit));
        log.info("Початок імпорту файлу: {}; Lucene фіналізується один раз після DB batches", context.getFile());

        if (estimatedCount > 10000) {
            bulkImportOptimizer.enableBulkInsertMode();
        }

        try {
            var importer = importerRegistry.findImporter(context.getFile());
            DuplicatePolicy policy = context.isUpdateExisting() ? DuplicatePolicy.MERGE : DuplicatePolicy.SKIP;
            List<Book> batch = new ArrayList<>(batchSize);

            // Оновлюємо прогрес на початку
            reportProgress(importProgressListener, 0.0, estimatedCount, totalProcessed, lastReported, startTime);
            emitOperation(telemetry, OperationProgress.stage(operationId, OperationStage.IMPORTING, true)
                    .withProgress(0, estimatedCount)
                    .withCurrentItem(context.getFile().getFileName().toString()));

            try (Stream<Book> bookStream = importer.importBooks(context.getFile())) {
                var iterator = enrichWithCollectionRoot(bookStream, context.getRootDirectory()).iterator();
                while (iterator.hasNext()) {
                    if (context.getCancelFlag() != null && context.getCancelFlag().get()) {
                        log.info("Імпорт файлу скасовано користувачем");
                        break;
                    }
                    Book book = iterator.next();
                    if (book == null) continue;
                    batch.add(book);

                    // Оновлюємо прогрес після кожного додавання
                    totalProcessed.incrementAndGet();

                    // Репортимо прогрес кожні 100 книг
                    if (totalProcessed.get() % 100 == 0) {
                        reportProgress(importProgressListener,
                                estimatedCount > 0 ? (double) totalProcessed.get() / estimatedCount : -1.0,
                                estimatedCount, totalProcessed, lastReported, startTime);
                        emitOperation(telemetry, OperationProgress.stage(operationId, OperationStage.IMPORTING, true)
                                .withProgress(totalProcessed.get(), estimatedCount)
                                .withCounts(changes.insertedCount(), changes.updatedCount(), changes.deletedCount(),
                                        stats.getSkipped().get(), stats.getDuplicates().get(), 0, stats.getErrors().get())
                                .withCurrentItem(context.getFile().getFileName().toString()));
                    }

                    if (batch.size() >= batchSize) {
                        saveLegacyBatch(batch, policy, stats, changes);
                        batch.clear();
                    }
                }
            }

            if (!batch.isEmpty()) {
                saveLegacyBatch(batch, policy, stats, changes);
                batch.clear();
            }

        } catch (Exception e) {
            log.error("Помилка імпорту файлу: {}", context.getFile(), e);
            ImportErrorHandler.ErrorAction action = errorHandler.handleError(context.getFile(), e, 1);
            if (action == ImportErrorHandler.ErrorAction.STOP_IMPORT) {
                throw new RuntimeException("Імпорт зупинено через критичну помилку", e);
            }
            stats.incrementErrors();
        } finally {
            if (estimatedCount > 10000) {
                bulkImportOptimizer.disableBulkInsertMode();
            }
        }

        ImportResult result = new ImportResult(
                stats.getImported().get(),
                stats.getSkipped().get(),
                stats.getDuplicates().get(),
                stats.getErrors().get(),
                stats.getDurationMs(),
                isCancelled(context) ? ImportStatus.CANCELLED
                        : (stats.getErrors().get() > 0 ? ImportStatus.SUCCESS_WITH_WARNINGS : ImportStatus.SUCCESS),
                changes.snapshot(),
                List.of());
        log.info("Імпорт файлу завершено: {}", result);

        if (context.isIndexAfterSave() && requiresSearchFinalization(result)) {
            emitOperation(telemetry, OperationProgress.stage(operationId, OperationStage.UPDATING_SEARCH_INDEX, true)
                    .withCounts(result.changes().insertedCount(), result.changes().updatedCount(), result.changes().deletedCount(),
                            result.skipped(), result.duplicates(), result.issues().size(), result.errors()));
            if (context.getStatusConsumer() != null) context.getStatusConsumer().accept("Оновлення пошукового індексу…");
            if (context.getProgressListener() != null) context.getProgressListener().accept(-1.0);
            if (result.changes().complete()) {
                applyIncrementalIndex(result.changes());
                log.info("📌 Selective Lucene update після legacy import: +{}, ~{}, -{}",
                        result.changes().insertedCount(), result.changes().updatedCount(), result.changes().deletedCount());
            } else {
                searchIndexer.rebuildIndex();
                log.info("📌 Full Lucene rebuild після bounded legacy change-tracking overflow");
            }
        }

        // 100% means both DB import and requested search synchronization have completed.
        // Cancellation may leave already committed legacy batches synchronized, but is not completion.
        if (!isCancelled(context)) {
            reportProgress(progressListener, 1.0, estimatedCount, totalProcessed, lastReported, startTime);
            if (context.isPublishFinishedEvent()) {
                emitOperation(telemetry, OperationProgress.stage(operationId, OperationStage.COMPLETED, false)
                        .withProgress(totalProcessed.get(), estimatedCount > 0 ? estimatedCount : totalProcessed.get())
                        .withCounts(result.changes().insertedCount(), result.changes().updatedCount(), result.changes().deletedCount(),
                                result.skipped(), result.duplicates(), result.issues().size(), result.errors()));
            }
        } else {
            emitOperation(telemetry, OperationProgress.stage(operationId, OperationStage.CANCELLED, false)
                    .withProgress(totalProcessed.get(), estimatedCount)
                    .withCounts(result.changes().insertedCount(), result.changes().updatedCount(), result.changes().deletedCount(),
                            result.skipped(), result.duplicates(), result.issues().size(), result.errors()));
        }
        publishFinished(context, result);
        return result;
    }

    private void saveLegacyBatch(List<Book> batch,
                                 DuplicatePolicy policy,
                                 ImportStatistics stats,
                                 ImportChangeAccumulator changes) {
        BookSaver.BatchSaveResult saveResult = bookSaver.saveBatchWithResult(batch, false, policy);
        int savedCount = saveResult.insertedBooks().size() + saveResult.updatedBooks().size();
        stats.incrementImported(savedCount);
        stats.getSkipped().addAndGet(saveResult.skippedBooks().size());
        stats.getDuplicates().addAndGet(saveResult.skippedBooks().size());
        for (Book inserted : saveResult.insertedBooks()) changes.recordInserted(inserted.getId().asString());
        for (Book updated : saveResult.updatedBooks()) changes.recordUpdated(updated.getId().asString());
    }

    // ==================== ДОПОМІЖНІ МЕТОДИ ====================

    private void publishFinished(ImportContext context, ImportResult result) {
        if (context != null && context.isPublishFinishedEvent()) {
            eventPublisher.publish(new com.myhomelibcorp.application.event.ImportFinishedEvent(context.getFile(), result));
        }
    }

    /**
     * Репортит прогрес через listener.
     */
    private void reportProgress(DoubleConsumer progressListener, double progress,
                                long estimatedCount, AtomicLong totalProcessed,
                                AtomicLong lastReported, long startTime) {
        if (progressListener == null) return;

        double safeProgress = progress < 0 ? -1.0 : Math.max(0.0, Math.min(1.0, progress));
        progressListener.accept(safeProgress);

        // Логуємо швидкість кожні 1000 книг
        long processed = totalProcessed.get();
        long last = lastReported.get();
        if (processed - last >= 1000 || processed == estimatedCount) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > 0 && processed > 0) {
                double speed = processed * 1000.0 / elapsed;
                if (safeProgress >= 0) {
                    log.info("⏳ Прогрес: {} / {} книг ({}%) - {} книг/с",
                            processed, estimatedCount, Math.round(safeProgress * 100), String.format(java.util.Locale.ROOT, "%.1f", speed));
                } else {
                    log.info("⏳ Прогрес: {} книг - {} книг/с",
                            processed, String.format(java.util.Locale.ROOT, "%.1f", speed));
                }
            }
            lastReported.set(processed);
        }
    }


    private static DoubleConsumer scaleImportPhaseProgress(DoubleConsumer listener, boolean hasFinalizationStage) {
        if (listener == null || !hasFinalizationStage) return listener;
        return value -> listener.accept(value < 0 ? -1.0 : Math.max(0.0, Math.min(0.85, value * 0.85)));
    }

    private static String operationId(ImportContext context, String prefix) {
        return context != null && context.getOperationId() != null && !context.getOperationId().isBlank()
                ? context.getOperationId()
                : prefix + UUID.randomUUID();
    }

    private static long processedForResult(ImportResult result) {
        if (result == null) return 0L;
        return Math.max(0L, result.imported() + result.skipped() + result.duplicates() + result.errors());
    }

    private static void emitOperation(Consumer<OperationProgress> listener, OperationProgress progress) {
        if (listener == null || progress == null) return;
        try { listener.accept(progress); }
        catch (RuntimeException callbackFailure) { log.debug("Operation progress callback failed", callbackFailure); }
    }

    private static boolean isCancelled(ImportContext context) {
        return context != null && context.getCancelFlag() != null && context.getCancelFlag().get();
    }

    private Stream<Book> enrichWithCollectionRoot(Stream<Book> bookStream, Path rootDirectory) {
        if (rootDirectory == null) {
            return bookStream;
        }
        String root = rootDirectory.toString();
        return bookStream.map(book -> {
            if (book == null) return null;
            if (book.getFile() != null && book.getFile().getCollectionRoot() != null && !book.getFile().getCollectionRoot().isEmpty()) {
                return book;
            }
            BookFile oldFile = book.getFile();
            BookFile newFile = new BookFile(
                    oldFile != null ? oldFile.getFileName() : "",
                    oldFile != null ? oldFile.getFolder() : "",
                    oldFile != null ? oldFile.getArchiveEntry() : "",
                    oldFile != null ? oldFile.getFileSize() : 0,
                    root
            );
            return Book.builder()
                    .id(book.getId())
                    .title(book.getTitle())
                    .authors(book.getAuthors())
                    .genres(book.getGenres())
                    .series(book.getSeries())
                    .sequenceNumber(book.getSequenceNumber())
                    .metadata(book.getMetadata())
                    .file(newFile)
                    .cover(book.getCover())
                    .updateDate(book.getUpdateDate())
                    .createdAt(book.getCreatedAt())
                    .deleted(book.isDeleted())
                    .local(book.isLocal())
                    .missingSince(book.getMissingSince())
                    .build();
        });
    }

}
