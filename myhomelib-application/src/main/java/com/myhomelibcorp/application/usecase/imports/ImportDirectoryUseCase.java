package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.application.imports.statistics.ImportChangeSet;
import com.myhomelibcorp.application.imports.statistics.ImportStatus;
import com.myhomelibcorp.application.imports.statistics.ImportStatistics;
import com.myhomelibcorp.application.imports.scanner.LibraryScanner;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseInitializerPort;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.search.SearchIndexSynchronizer;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportDirectoryUseCase {

    @Value("${app.import.batch-size:1000}")
    private int defaultBatchSize;

    @Value("${app.import.change-tracking-limit:50000}")
    private int changeTrackingLimit;

    private final ImportFileUseCase importFileUseCase;
    private final LibraryScanner libraryScanner;
    private final EventPublisher eventPublisher;
    private final IndexRebuilder indexRebuilder;
    private final SearchIndexSynchronizer searchIndexSynchronizer;
    private final BulkImportOptimizer bulkImportOptimizer;
    private final DatabaseInitializerPort databaseInitializerPort;


    public ImportResult execute(ImportContext context) {
        // ЄДИНИЙ виклик ініціалізації на весь імпорт каталогу
        databaseInitializerPort.initializeCurrentCollection();

        Path directory = context.getRootDirectory();
        if (directory == null) {
            throw new IllegalArgumentException("Root directory cannot be null");
        }

        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;

        log.info("Початок імпорту каталогу: {}", directory);
        ImportStatistics totalStats = new ImportStatistics();
        ImportChangeAccumulator changes = new ImportChangeAccumulator(
                ImportChangeAccumulator.normalizeLimit(changeTrackingLimit));

        bulkImportOptimizer.enableBulkInsertMode();

        boolean cancelled = false;
        boolean childReportedWarningOrFailure = false;
        try {
            AtomicLong processed = new AtomicLong(0);
            if (context.getProgressListener() != null) context.getProgressListener().accept(-1.0);
            if (context.getStatusConsumer() != null) context.getStatusConsumer().accept("Сканування та імпорт каталогу…");

            try (Stream<Path> files = libraryScanner.streamSupportedFiles(directory)) {
                var iterator = files.iterator();
                while (iterator.hasNext()) {
                    if (isCancelled(context)) {
                        cancelled = true;
                        log.info("Імпорт скасовано");
                        break;
                    }
                    Path file = iterator.next();
                    try {
                        ImportContext fileContext = ImportContext.builder()
                                .file(file)
                                .rootDirectory(directory)
                                .updateExisting(context.isUpdateExisting())
                                .indexAfterSave(false)
                                .publishFinishedEvent(false)
                                .batchSize(batchSize)
                                .cancelFlag(context.getCancelFlag())
                                .progressListener(null)
                                .build();

                        ImportResult result = importFileUseCase.execute(fileContext);
                        totalStats.getImported().addAndGet(result.imported());
                        totalStats.getSkipped().addAndGet(result.skipped());
                        totalStats.getDuplicates().addAndGet(result.duplicates());
                        totalStats.getErrors().addAndGet(result.errors());
                        changes.merge(result.changes());
                        if (result.status() == ImportStatus.CANCELLED) {
                            cancelled = true;
                            log.info("Дочірній імпорт скасовано: {}", file);
                            break;
                        }
                        if (result.status() != ImportStatus.SUCCESS) {
                            childReportedWarningOrFailure = true;
                        }
                    } catch (Exception e) {
                        log.error("Помилка імпорту файлу: {}", file, e);
                        totalStats.incrementErrors();
                    }
                    long processedCount = processed.incrementAndGet();
                    if (context.getStatusConsumer() != null && processedCount % 1000 == 0) {
                        context.getStatusConsumer().accept("Оброблено файлів: " + processedCount);
                    }
                }
            }

        } catch (IOException e) {
            log.error("Помилка сканування каталогу: {}", directory, e);
            throw new RuntimeException("Помилка сканування каталогу", e);
        } finally {
            bulkImportOptimizer.disableBulkInsertMode();
        }

        cancelled = cancelled || isCancelled(context);
        ImportChangeSet changeSet = changes.snapshot();
        ImportResult finalResult = new ImportResult(
                totalStats.getImported().get(),
                totalStats.getSkipped().get(),
                totalStats.getDuplicates().get(),
                totalStats.getErrors().get(),
                totalStats.getDurationMs(),
                cancelled ? ImportStatus.CANCELLED
                        : (totalStats.getErrors().get() > 0 || childReportedWarningOrFailure
                                ? ImportStatus.SUCCESS_WITH_WARNINGS : ImportStatus.SUCCESS),
                changeSet,
                java.util.List.of());
        log.info("Імпорт каталогу завершено. {}", finalResult);

        if (context.isIndexAfterSave() && requiresSearchFinalization(finalResult)) {
            if (context.getProgressListener() != null) context.getProgressListener().accept(-1.0);
            if (context.getStatusConsumer() != null) context.getStatusConsumer().accept("Синхронізація пошукового індексу…");
            if (changeSet.complete()) {
                java.util.LinkedHashSet<String> changedIds = new java.util.LinkedHashSet<>(changeSet.inserted());
                changedIds.addAll(changeSet.updated());
                changedIds.addAll(changeSet.deleted());
                if (changedIds.isEmpty()) {
                    log.warn("Імпорт додав {} книг без exact change IDs; виконується safe full Lucene rebuild",
                            totalStats.getImported().get());
                    indexRebuilder.rebuildIndex();
                } else {
                    java.util.List<BookId> ids = changedIds.stream().map(BookId::fromString).toList();
                    if (!searchIndexSynchronizer.synchronizeSafelyNow(ids)) {
                        throw new IllegalStateException("Не вдалося синхронізувати пошуковий індекс після імпорту каталогу");
                    }
                    log.info("Selective Lucene sync після імпорту каталогу: {} IDs", ids.size());
                }
            } else {
                log.info("Change tracking перевищив bounded threshold; один full Lucene rebuild після імпорту каталогу");
                indexRebuilder.rebuildIndex();
            }
        }
        // 100% is reserved for a fully completed operation. A cancelled directory import may still
        // synchronize already committed partial changes, but must not masquerade as completed.
        if (!cancelled && context.getProgressListener() != null) context.getProgressListener().accept(1.0);
        if (context.getStatusConsumer() != null) {
            context.getStatusConsumer().accept(cancelled ? "Імпорт скасовано" : "Імпорт каталогу завершено");
        }

        if (context.isPublishFinishedEvent()) {
            eventPublisher.publish(new com.myhomelibcorp.application.event.ImportFinishedEvent(directory, finalResult));
        }
        return finalResult;
    }
    private static boolean requiresSearchFinalization(ImportResult result) {
        if (result == null) return false;
        ImportChangeSet changes = result.changes();
        if (changes == null) return result.imported() > 0;
        long changedRows = changes.insertedCount() + changes.updatedCount() + changes.deletedCount();
        // Compatibility/legacy importers may report successful writes without exact IDs. Those rows
        // still require a safe rebuild rather than leaving Lucene stale. Deleted-only snapshots also
        // need finalization even when imported == 0.
        return changedRows > 0 || result.imported() > 0;
    }

    private static boolean isCancelled(ImportContext context) {
        return context != null && context.getCancelFlag() != null && context.getCancelFlag().get();
    }

}
