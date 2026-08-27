package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.imports.error.ImportErrorHandler;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatistics;
import com.myhomelibcorp.application.port.out.cache.CacheRefresherPort;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.importer.FastImportService;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.port.out.infrastructure.BulkImportOptimizer;
import com.myhomelibcorp.application.port.out.search.IndexRebuilder;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

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
    private final CacheRefresherPort cacheRefresherPort;
    private final SearchIndexer searchIndexer;

    @Value("${app.import.batch-size:500}")
    private int defaultBatchSize;

    private static final long INDEX_DISABLE_THRESHOLD = 500_000;

    // ==================== ОСНОВНИЙ МЕТОД ====================

    public ImportResult execute(ImportContext context) {
        if (context == null || context.getFile() == null) {
            throw new IllegalArgumentException("File cannot be null");
        }

        String fileName = context.getFile().getFileName().toString().toLowerCase();
        if (fileName.endsWith(".inpx") || fileName.endsWith(".inp")) {
            return executeInpx(context);
        }

        return executeLegacy(context);
    }

    // ==================== МЕТОДИ З ПРОГРЕСОМ ====================

    /**
     * Виконує імпорт з передачею прогресу через DoubleConsumer.
     * Значення progress від 0.0 до 1.0.
     */
    public ImportResult executeWithProgress(ImportContext context, DoubleConsumer progressConsumer) {
        if (context == null || context.getFile() == null) {
            throw new IllegalArgumentException("File cannot be null");
        }

        // Додаємо progressConsumer до контексту
        ImportContext contextWithProgress = ImportContext.builder()
                .file(context.getFile())
                .rootDirectory(context.getRootDirectory())
                .archiveEntry(context.getArchiveEntry())
                .catalogSourceKey(context.getCatalogSourceKey())
                .catalogSourceLocation(context.getCatalogSourceLocation())
                .updateExisting(context.isUpdateExisting())
                .indexAfterSave(context.isIndexAfterSave())
                .catalogFullSnapshot(context.isCatalogFullSnapshot())
                .progressListener(progressConsumer)
                .statusConsumer(context.getStatusConsumer())
                .cancelFlag(context.getCancelFlag())
                .batchSize(context.getBatchSize())
                .build();

        return execute(contextWithProgress);
    }

    /**
     * Виконує імпорт з передачею прогресу та статусу.
     */
    public ImportResult executeWithProgressAndStatus(ImportContext context,
                                                     DoubleConsumer progressConsumer,
                                                     Consumer<String> statusConsumer) {
        if (context == null || context.getFile() == null) {
            throw new IllegalArgumentException("File cannot be null");
        }

        ImportContext contextWithProgress = ImportContext.builder()
                .file(context.getFile())
                .rootDirectory(context.getRootDirectory())
                .archiveEntry(context.getArchiveEntry())
                .catalogSourceKey(context.getCatalogSourceKey())
                .catalogSourceLocation(context.getCatalogSourceLocation())
                .updateExisting(context.isUpdateExisting())
                .indexAfterSave(context.isIndexAfterSave())
                .catalogFullSnapshot(context.isCatalogFullSnapshot())
                .progressListener(progressConsumer)
                .statusConsumer(statusConsumer)
                .cancelFlag(context.getCancelFlag())
                .batchSize(context.getBatchSize())
                .build();

        return execute(contextWithProgress);
    }

    /**
     * Виконує імпорт з детальним прогресом для UI.
     * Повертає ImportResult та оновлює прогреси.
     */
    public ImportResult executeWithDetailedProgress(ImportContext context,
                                                    DoubleConsumer overallProgress,
                                                    Consumer<String> statusConsumer,
                                                    Consumer<Long> processedCountConsumer,
                                                    Consumer<Double> speedConsumer) {
        if (context == null || context.getFile() == null) {
            throw new IllegalArgumentException("File cannot be null");
        }

        // Додаємо всі колбеки до контексту
        ImportContext contextWithProgress = ImportContext.builder()
                .file(context.getFile())
                .rootDirectory(context.getRootDirectory())
                .archiveEntry(context.getArchiveEntry())
                .catalogSourceKey(context.getCatalogSourceKey())
                .catalogSourceLocation(context.getCatalogSourceLocation())
                .updateExisting(context.isUpdateExisting())
                .indexAfterSave(context.isIndexAfterSave())
                .catalogFullSnapshot(context.isCatalogFullSnapshot())
                .progressListener(overallProgress)
                .statusConsumer(statusConsumer)
                .cancelFlag(context.getCancelFlag())
                .batchSize(context.getBatchSize())
                .build();

        return execute(contextWithProgress);
    }

    // ==================== ВНУТРІШНІ МЕТОДИ ====================

    private ImportResult executeInpx(ImportContext context) {
        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;
        Path rootDirectory = context.getRootDirectory();

        ImportResult result = fastImportService.importInpx(
                context.getFile(),
                batchSize,
                rootDirectory,
                context.getCancelFlag(),
                context.getCatalogSourceKey(),
                context.getCatalogSourceLocation(),
                context.isCatalogFullSnapshot(),
                context.getProgressListener(),
                context.getStatusConsumer());

        if (result.imported() > 0) {
            cacheRefresherPort.refreshCachesAsync();
            log.info("Запущено асинхронне оновлення малих словникових кешів після {} книг", result.imported());

            try {
                searchIndexer.commit();
                log.info("📌 Індекс закомічено після імпорту {} книг", result.imported());
            } catch (Exception e) {
                log.warn("Не вдалося закомітити індекс після імпорту", e);
            }
        }

        eventPublisher.publish(new com.myhomelibcorp.application.event.ImportFinishedEvent(context.getFile(), result));
        return result;
    }

    private ImportResult executeLegacy(ImportContext context) {
        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;
        boolean indexAfterSave = context.isIndexAfterSave();

        // Отримуємо progress listener з контексту
        DoubleConsumer progressListener = context.getProgressListener();
        AtomicLong totalProcessed = new AtomicLong(0);
        AtomicLong lastReported = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        long estimatedCount = -1;
        try {
            var importer = importerRegistry.findImporter(context.getFile());
            estimatedCount = importer.countBooks(context.getFile());
            if (estimatedCount > INDEX_DISABLE_THRESHOLD) {
                indexAfterSave = false;
                log.info("Файл містить {} книг (поріг {}), індексацію вимкнено для прискорення",
                        estimatedCount, INDEX_DISABLE_THRESHOLD);
            }
        } catch (Exception e) {
            log.debug("Не вдалося оцінити кількість книг, індексація залишена увімкненою");
        }

        ImportStatistics stats = new ImportStatistics();
        log.info("Початок імпорту файлу: {}, індексація: {}", context.getFile(), indexAfterSave);

        if (estimatedCount > 10000) {
            bulkImportOptimizer.enableBulkInsertMode();
        }

        try {
            var importer = importerRegistry.findImporter(context.getFile());
            DuplicatePolicy policy = DuplicatePolicy.SKIP;
            List<Book> batch = new ArrayList<>(batchSize);

            // Оновлюємо прогрес на початку
            reportProgress(progressListener, 0.0, estimatedCount, totalProcessed, lastReported, startTime);

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
                        reportProgress(progressListener,
                                (double) totalProcessed.get() / Math.max(1, estimatedCount),
                                estimatedCount, totalProcessed, lastReported, startTime);
                    }

                    if (batch.size() >= batchSize) {
                        int attempted = batch.size();
                        int saved = bookSaver.saveBatch(batch, indexAfterSave, policy);
                        stats.incrementImported(saved);
                        stats.getSkipped().addAndGet(attempted - saved);
                        batch.clear();
                    }
                }
            }

            if (!batch.isEmpty()) {
                int attempted = batch.size();
                int saved = bookSaver.saveBatch(batch, indexAfterSave, policy);
                stats.incrementImported(saved);
                stats.getSkipped().addAndGet(attempted - saved);
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

        // Фінальний прогрес
        reportProgress(progressListener, 1.0, estimatedCount, totalProcessed, lastReported, startTime);

        ImportResult result = ImportResult.fromStatistics(stats);
        log.info("Імпорт файлу завершено: {}", result);

        if (stats.getImported().get() > 0) {
            try {
                searchIndexer.commit();
                log.info("📌 Індекс закомічено після імпорту {} книг", stats.getImported().get());
            } catch (Exception e) {
                log.warn("Не вдалося закомітити індекс після імпорту", e);
            }

            cacheRefresherPort.refreshCachesAsync();
            log.info("Запущено асинхронне оновлення кешів словників після legacy-імпорту");
        }

        eventPublisher.publish(new com.myhomelibcorp.application.event.ImportFinishedEvent(context.getFile(), result));
        return result;
    }

    // ==================== ДОПОМІЖНІ МЕТОДИ ====================

    /**
     * Репортит прогрес через listener.
     */
    private void reportProgress(DoubleConsumer progressListener, double progress,
                                long estimatedCount, AtomicLong totalProcessed,
                                AtomicLong lastReported, long startTime) {
        if (progressListener == null) return;

        double safeProgress = Math.max(0.0, Math.min(1.0, progress));
        progressListener.accept(safeProgress);

        // Логуємо швидкість кожні 1000 книг
        long processed = totalProcessed.get();
        long last = lastReported.get();
        if (processed - last >= 1000 || processed == estimatedCount) {
            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed > 0 && processed > 0) {
                double speed = processed * 1000.0 / elapsed;
                log.info("⏳ Прогрес: {} / {} книг ({}%) - {:.1f} книг/с",
                        processed, estimatedCount,
                        Math.round(safeProgress * 100), speed);
            }
            lastReported.set(processed);
        }
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
                    .build();
        });
    }

    // ==================== ДОДАТКОВІ МЕТОДИ ДЛЯ ЗРУЧНОСТІ ====================

    /**
     * Створює ImportContext з додатковими колбеками прогресу.
     */
    public static ImportContext createContextWithProgress(Path file,
                                                          Path rootDirectory,
                                                          DoubleConsumer progressListener,
                                                          Consumer<String> statusConsumer,
                                                          int batchSize) {
        return ImportContext.builder()
                .file(file)
                .rootDirectory(rootDirectory)
                .batchSize(batchSize)
                .indexAfterSave(true)
                .progressListener(progressListener)
                .statusConsumer(statusConsumer)
                .build();
    }

    /**
     * Створює ImportContext для INPX з прогресами.
     */
    public static ImportContext createInpxContext(Path file,
                                                  Path rootDirectory,
                                                  String catalogSourceKey,
                                                  String catalogSourceLocation,
                                                  DoubleConsumer progressListener,
                                                  Consumer<String> statusConsumer) {
        return ImportContext.builder()
                .file(file)
                .rootDirectory(rootDirectory)
                .catalogSourceKey(catalogSourceKey)
                .catalogSourceLocation(catalogSourceLocation)
                .catalogFullSnapshot(false)
                .batchSize(5000)
                .updateExisting(true)
                .indexAfterSave(false)
                .progressListener(progressListener)
                .statusConsumer(statusConsumer)
                .build();
    }
}