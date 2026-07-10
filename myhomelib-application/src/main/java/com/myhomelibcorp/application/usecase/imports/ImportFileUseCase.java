package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.imports.error.ImportErrorHandler;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatistics;
import com.myhomelibcorp.application.imports.transaction.ImportTransaction;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.port.out.importer.FastImportService;
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
    private final ImportTransaction importTransaction;
    private final ImportErrorHandler errorHandler;
    private final EventPublisher eventPublisher;
    private final IndexRebuilder indexRebuilder;
    private final BulkImportOptimizer bulkImportOptimizer;
    private final FastImportService fastImportService;

    @Value("${app.import.batch-size:500}")
    private int defaultBatchSize;

    private static final long INDEX_DISABLE_THRESHOLD = 500_000;

    @Deprecated
    public int execute(Path file) {
        ImportContext context = ImportContext.builder()
                .file(file)
                .batchSize(defaultBatchSize)
                .indexAfterSave(true)
                .build();
        ImportResult result = execute(context);
        return (int) result.imported();
    }

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

        long count = fastImportService.importInpx(context.getFile(), batchSize, rootDirectory);

        ImportStatistics stats = new ImportStatistics();
        stats.incrementImported((int) count);

        ImportResult result = ImportResult.fromStatistics(stats);
        eventPublisher.publish(new com.myhomelibcorp.application.event.ImportFinishedEvent(context.getFile(), result));
        return result;
    }

    private ImportResult executeLegacy(ImportContext context) {
        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;
        boolean indexAfterSave = context.isIndexAfterSave();

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
            try (Stream<Book> bookStream = importer.importBooks(context.getFile())) {
                // Збагачуємо книги collectionRoot, якщо вони ще не мають
                Stream<Book> enrichedStream = enrichWithCollectionRoot(bookStream, context.getRootDirectory());
                saveBooksBatch(enrichedStream, context, stats, indexAfterSave);
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

        if (!indexAfterSave && stats.getImported().get() > 0) {
            log.info("Перебудова індексу після імпорту (був вимкнений)");
            indexRebuilder.rebuildIndex();
        }

        eventPublisher.publish(new com.myhomelibcorp.application.event.ImportFinishedEvent(context.getFile(), result));
        return result;
    }

    /**
     * Додає collectionRoot до книг, якщо вони ще не мають його.
     */
    private Stream<Book> enrichWithCollectionRoot(Stream<Book> bookStream, Path rootDirectory) {
        if (rootDirectory == null) {
            return bookStream;
        }
        String root = rootDirectory.toString();
        return bookStream.map(book -> {
            if (book == null) return null;
            if (book.getFile() != null && book.getFile().getCollectionRoot() != null && !book.getFile().getCollectionRoot().isEmpty()) {
                // Вже має collectionRoot – залишаємо як є
                return book;
            }
            // Створюємо новий BookFile з collectionRoot
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

    private void saveBooksBatch(Stream<Book> bookStream, ImportContext context, ImportStatistics stats, boolean indexAfterSave) {
        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;
        List<Book> batch = new ArrayList<>(batchSize);
        DuplicatePolicy policy = DuplicatePolicy.SAVE_AS_NEW;
        int totalSaved = 0;

        try (Stream<Book> stream = bookStream) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                if (context.getCancelFlag() != null && context.getCancelFlag().get()) {
                    log.info("Імпорт скасовано");
                    break;
                }

                Book book = iterator.next();
                if (book != null) {
                    batch.add(book);
                    if (batch.size() >= batchSize) {
                        int saved = importTransaction.saveBatchInTransaction(batch, indexAfterSave, policy);
                        totalSaved += saved;
                        stats.incrementImported(saved);
                        stats.getSkipped().addAndGet(batch.size() - saved);
                        batch.clear();

                        if (context.getStatusConsumer() != null) {
                            context.getStatusConsumer().accept("Оброблено " + totalSaved + " книг");
                        }
                        if (context.getProgressListener() != null) {
                            context.getProgressListener().accept((double) totalSaved);
                        }
                    }
                }
            }

            if (!batch.isEmpty()) {
                int saved = importTransaction.saveBatchInTransaction(batch, indexAfterSave, policy);
                totalSaved += saved;
                stats.incrementImported(saved);
                stats.getSkipped().addAndGet(batch.size() - saved);
                if (context.getStatusConsumer() != null) {
                    context.getStatusConsumer().accept("Оброблено " + totalSaved + " книг");
                }
            }

        } catch (Exception e) {
            log.error("Помилка збереження батча", e);
            stats.incrementErrors();
        }
    }
}