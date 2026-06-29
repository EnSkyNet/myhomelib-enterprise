package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.port.in.imports.ImportInpxUseCase;
import com.myhomelibcorp.application.port.out.ImporterRegistry;
import com.myhomelibcorp.application.port.out.BookCommandRepository;
import com.myhomelibcorp.domain.event.book.BookImportedEvent;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportFileUseCase {

    private static final int BATCH_SIZE = 500;

    private final ImporterRegistry importerRegistry;
    private final BookCommandRepository bookCommandRepository;
    private final ApplicationEventPublisher eventPublisher;

    public int execute(Path file) {
        log.info("Початок імпорту файлу: {}", file);
        var importer = importerRegistry.findImporter(file);
        try (Stream<Book> bookStream = importer.importBooks(file)) {
            return saveBooksBatch(bookStream);
        } catch (Exception e) {
            log.error("Помилка імпорту файлу: {}", file, e);
            throw new RuntimeException("Помилка імпорту", e);
        }
    }

    private int saveBooksBatch(Stream<Book> bookStream) {
        List<Book> batch = new ArrayList<>(BATCH_SIZE);
        int totalSaved = 0;
        int batchCounter = 0;

        try (Stream<Book> stream = bookStream) {
            var iterator = stream.iterator();
            while (iterator.hasNext()) {
                Book book = iterator.next();
                if (book != null) {
                    batch.add(book);
                    if (batch.size() >= BATCH_SIZE) {
                        totalSaved += saveBatch(batch);
                        batch.clear();
                        batchCounter++;
                        log.debug("Збережено батч #{} ({} книг)", batchCounter, BATCH_SIZE);
                    }
                }
            }
            if (!batch.isEmpty()) {
                totalSaved += saveBatch(batch);
                log.debug("Збережено останній батч ({} книг)", batch.size());
            }
        }

        log.info("Всього збережено {} книг", totalSaved);
        return totalSaved;
    }

    private int saveBatch(List<Book> batch) {
        log.info("📚 Отримано {} книг з файлу", batch.size());
        int saved = 0;
        for (Book book : batch) {
            bookCommandRepository.save(book);
            log.debug("Публікація події для книги: {}", book.getId().asString());
            eventPublisher.publishEvent(new BookImportedEvent(book.getId()));
            saved++;
        }
        return saved;
    }
}