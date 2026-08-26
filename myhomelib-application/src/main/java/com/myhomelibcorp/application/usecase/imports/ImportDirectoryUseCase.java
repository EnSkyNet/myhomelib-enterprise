package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatistics;
import com.myhomelibcorp.application.imports.scanner.LibraryScanner;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.infrastructure.DatabaseInitializerPort;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
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

    @Value("${app.import.batch-size:500}")
    private int defaultBatchSize;

    private final ImportFileUseCase importFileUseCase;
    private final LibraryScanner libraryScanner;
    private final EventPublisher eventPublisher;
    private final IndexRebuilder indexRebuilder;
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
        if (context.getBatchSize() <= 0) {
            context = ImportContext.builder()
                    .rootDirectory(directory)
                    .updateExisting(context.isUpdateExisting())
                    .indexAfterSave(context.isIndexAfterSave())
                    .batchSize(batchSize)
                    .cancelFlag(context.getCancelFlag())
                    .progressListener(context.getProgressListener())
                    .build();
        }

        log.info("Початок імпорту каталогу: {}", directory);
        ImportStatistics totalStats = new ImportStatistics();

        bulkImportOptimizer.enableBulkInsertMode();

        try {
            long totalFiles = libraryScanner.countSupportedFiles(directory);
            log.info("Знайдено {} файлів для імпорту", totalFiles);
            AtomicLong processed = new AtomicLong(0);

            try (Stream<Path> files = libraryScanner.streamSupportedFiles(directory)) {
                var iterator = files.iterator();
                while (iterator.hasNext()) {
                    if (context.getCancelFlag() != null && context.getCancelFlag().get()) {
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
                                .batchSize(batchSize)
                                .cancelFlag(context.getCancelFlag())
                                .progressListener(context.getProgressListener())
                                .build();

                        ImportResult result = importFileUseCase.execute(fileContext);
                        totalStats.getImported().addAndGet(result.imported());
                        totalStats.getSkipped().addAndGet(result.skipped());
                        totalStats.getDuplicates().addAndGet(result.duplicates());
                        totalStats.getErrors().addAndGet(result.errors());
                    } catch (Exception e) {
                        log.error("Помилка імпорту файлу: {}", file, e);
                        totalStats.incrementErrors();
                    }
                    long processedCount = processed.incrementAndGet();
                    if (context.getProgressListener() != null && totalFiles > 0) {
                        context.getProgressListener().accept((double) processedCount / totalFiles);
                    }
                }
            }

        } catch (IOException e) {
            log.error("Помилка сканування каталогу: {}", directory, e);
            throw new RuntimeException("Помилка сканування каталогу", e);
        } finally {
            bulkImportOptimizer.disableBulkInsertMode();
        }

        ImportResult finalResult = ImportResult.fromStatistics(totalStats);
        log.info("Імпорт каталогу завершено. {}", finalResult);

        if (totalStats.getImported().get() > 0) {
            log.info("Перебудова індексу після імпорту каталогу");
            indexRebuilder.rebuildIndex();
        }

        eventPublisher.publish(new com.myhomelibcorp.application.event.ImportFinishedEvent(directory, finalResult));
        return finalResult;
    }
}