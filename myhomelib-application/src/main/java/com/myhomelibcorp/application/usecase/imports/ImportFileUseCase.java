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
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportFileUseCase {

    private final ImporterRegistry importerRegistry;
    private final BookSaver bookSaver;
    private final ImportErrorHandler errorHandler;
    private final EventPublisher eventPublisher;
    private final IndexRebuilder indexRebuilder;
    private final BulkImportOptimizer bulkImportOptimizer;
    private final FastImportService fastImportService;
    private final CacheRefresherPort cacheRefresherPort;

    @Value("${app.import.batch-size:500}")
    private int defaultBatchSize;

    private static final long INDEX_DISABLE_THRESHOLD = 500_000;


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
        }

        eventPublisher.publish(new com.myhomelibcorp.application.event.ImportFinishedEvent(context.getFile(), result));
        return result;
    }

    private ImportResult executeLegacy(ImportContext context) {
        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;
        boolean indexAfterSave = context.isIndexAfterSave();
        boolean rebuildIndexAfterImport = false;

        long estimatedCount = -1;
        try {
            var importer = importerRegistry.findImporter(context.getFile());
            estimatedCount = importer.countBooks(context.getFile());
            if (estimatedCount > INDEX_DISABLE_THRESHOLD) {
                indexAfterSave = false;
                rebuildIndexAfterImport = true;
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

        ImportResult result = ImportResult.fromStatistics(stats);
        log.info("Імпорт файлу завершено: {}", result);

        if (rebuildIndexAfterImport && stats.getImported().get() > 0) {
            log.info("Перебудова індексу після великого імпорту (індексацію тимчасово вимкнено)");
            indexRebuilder.rebuildIndex();
        }

        if (stats.getImported().get() > 0) {
            cacheRefresherPort.refreshCachesAsync();
            log.info("Запущено асинхронне оновлення кешів словників після legacy-імпорту");
        }

        eventPublisher.publish(new com.myhomelibcorp.application.event.ImportFinishedEvent(context.getFile(), result));
        return result;
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
}