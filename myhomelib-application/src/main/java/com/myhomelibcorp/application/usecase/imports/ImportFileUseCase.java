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

    // ========== СТАРИЙ МЕТОД (зворотна сумісність) ==========

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

    // ========== НОВИЙ МЕТОД ==========

    public ImportResult execute(ImportContext context) {
        if (context == null || context.getFile() == null) {
            throw new IllegalArgumentException("File cannot be null");
        }

        // Якщо в контексті не задано batchSize, беремо з конфігурації
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
                    .build();
        }

        ImportStatistics stats = new ImportStatistics();
        log.info("Початок імпорту файлу: {}", context.getFile());

        try {
            var importer = importerRegistry.findImporter(context.getFile());
            try (Stream<Book> bookStream = importer.importBooks(context.getFile())) {
                saveBooksBatch(bookStream, context, stats);
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

    // ========== ВНУТРІШНІ МЕТОДИ ==========

    private void saveBooksBatch(Stream<Book> bookStream, ImportContext context, ImportStatistics stats) {
        int batchSize = context.getBatchSize() > 0 ? context.getBatchSize() : defaultBatchSize;
        List<Book> batch = new ArrayList<>(batchSize);
        DuplicatePolicy policy = DuplicatePolicy.SKIP;

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
                        int saved = importTransaction.saveBatchInTransaction(
                                batch,
                                context.isIndexAfterSave(),
                                policy
                        );
                        stats.incrementImported(saved);
                        stats.getSkipped().addAndGet(batch.size() - saved);
                        batch.clear();
                        updateProgress(stats, context);
                    }
                }
            }

            // Збереження останнього неповного батча
            if (!batch.isEmpty()) {
                int saved = importTransaction.saveBatchInTransaction(
                        batch,
                        context.isIndexAfterSave(),
                        policy
                );
                stats.incrementImported(saved);
                stats.getSkipped().addAndGet(batch.size() - saved);
            }

        } catch (Exception e) {
            log.error("Помилка збереження батча", e);
            stats.incrementErrors();
        }
    }

    private void updateProgress(ImportStatistics stats, ImportContext context) {
        if (context.getProgressListener() != null) {
            context.getProgressListener().accept((double) stats.getImported().get());
        }
    }
}