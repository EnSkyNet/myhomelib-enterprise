package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.event.ImportFinishedEvent;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.imports.error.ImportErrorHandler;
import com.myhomelibcorp.application.imports.saver.BookSaver;
import com.myhomelibcorp.application.imports.statistics.ImportResult;
import com.myhomelibcorp.application.imports.statistics.ImportStatistics;
import com.myhomelibcorp.application.imports.transaction.ImportTransaction;
import com.myhomelibcorp.application.port.out.event.EventPublisher;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
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

        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;
        if (context.getBatchSize() <= 0) {
            context = ImportContext.builder()
                    .file(context.getFile())
                    .rootDirectory(context.getRootDirectory())
                    .updateExisting(context.isUpdateExisting())
                    .indexAfterSave(context.isIndexAfterSave())
                    .batchSize(batchSize)
                    .cancelFlag(context.getCancelFlag())
                    .progressListener(context.getProgressListener())
                    .statusConsumer(context.getStatusConsumer())
                    .build();
        }

        boolean indexAfterSave = context.isIndexAfterSave();
        long estimatedCount = -1;

        // ---- Оцінка кількості книг для вимкнення індексації ----
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

        try {
            var importer = importerRegistry.findImporter(context.getFile());
            try (Stream<Book> bookStream = importer.importBooks(context.getFile())) {
                saveBooksBatch(bookStream, context, stats, indexAfterSave);
            }
        } catch (Exception e) {
            log.error("Помилка імпорту файлу: {}", context.getFile(), e);
            ImportErrorHandler.ErrorAction action = errorHandler.handleError(context.getFile(), e, 1);
            if (action == ImportErrorHandler.ErrorAction.STOP_IMPORT) {
                throw new RuntimeException("Імпорт зупинено через критичну помилку", e);
            }
            stats.incrementErrors();
        }

        ImportResult result = ImportResult.fromStatistics(stats);
        log.info("Імпорт файлу завершено: {}", result);

        eventPublisher.publish(new ImportFinishedEvent(context.getFile(), result));
        return result;
    }

    private void saveBooksBatch(Stream<Book> bookStream, ImportContext context, ImportStatistics stats, boolean indexAfterSave) {
        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;
        List<Book> batch = new ArrayList<>(batchSize);
        // Для великих файлів використовуємо SAVE_AS_NEW, щоб уникнути перевірки дублікатів
        // та пришвидшити імпорт. Для малих файлів залишаємо SKIP.
        DuplicatePolicy policy = DuplicatePolicy.SAVE_AS_NEW;
        int totalSaved = 0;
        int batchCount = 0;

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
                        batchCount++;

                        // ---- Оновлення статусу ----
                        if (context.getStatusConsumer() != null) {
                            context.getStatusConsumer().accept("Оброблено " + totalSaved + " книг");
                        }
                        if (context.getProgressListener() != null) {
                            // Оновлюємо прогрес (приблизно)
                            context.getProgressListener().accept((double) totalSaved);
                        }
                    }
                }
            }

            // Збереження останнього неповного батча
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