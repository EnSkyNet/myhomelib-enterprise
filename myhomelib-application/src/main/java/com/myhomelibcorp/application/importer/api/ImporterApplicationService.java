package com.myhomelibcorp.application.importer.api;

import com.myhomelibcorp.application.port.in.imports.ImportInpxUseCase;
import com.myhomelibcorp.application.port.out.BookCommandRepository;
import com.myhomelibcorp.application.port.out.ImporterRegistry;
import com.myhomelibcorp.domain.event.book.BookImportedEvent;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImporterApplicationService implements ImportInpxUseCase {

    private final ImporterRegistry importerRegistry;
    private final BookCommandRepository bookCommandRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public int importInpx(Path file) {
        return importBooks(file);
    }

    @Transactional
    public int importBooks(Path file) {
        log.info("Початок імпорту файлу: {}", file);
        var importer = importerRegistry.findImporter(file);
        List<Book> books = importer.importBooks(file);
        int saved = saveBooks(books);
        log.info("Імпорт завершено. Збережено {} книг", saved);
        return saved;
    }

    @Transactional
    public int importDirectory(Path directory, DoubleConsumer progressConsumer, AtomicBoolean cancelled) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Шлях не є каталогом: " + directory);
        }

        log.info("Початок імпорту каталогу: {}", directory);
        AtomicInteger totalSaved = new AtomicInteger(0);
        AtomicInteger totalFiles = new AtomicInteger(0);
        AtomicInteger processedFiles = new AtomicInteger(0);
        AtomicInteger unsupportedFiles = new AtomicInteger(0);

        try (Stream<Path> pathStream = Files.walk(directory)) {
            List<Path> files = pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            boolean supported = importerRegistry.findImporter(path) != null;
                            if (!supported) {
                                unsupportedFiles.incrementAndGet();
                                log.debug("Непідтримуваний файл: {}", path.getFileName());
                            }
                            return supported;
                        } catch (IllegalArgumentException e) {
                            unsupportedFiles.incrementAndGet();
                            return false;
                        }
                    })
                    .toList();

            totalFiles.set(files.size());
            log.info("Знайдено {} підтримуваних файлів ({} непідтримуваних пропущено)",
                    totalFiles.get(), unsupportedFiles.get());

            int processedCount = 0;
            for (Path file : files) {
                if (cancelled != null && cancelled.get()) {
                    log.info("Імпорт скасовано користувачем");
                    throw new RuntimeException("Імпорт скасовано");
                }

                processedCount++;
                if (processedCount % 10 == 0) {
                    log.info("Оброблено {} з {} файлів", processedCount, totalFiles.get());
                }

                try {
                    int saved = importBooks(file);
                    totalSaved.addAndGet(saved);
                    log.debug("Файл {}: імпортовано {} книг", file.getFileName(), saved);
                } catch (Exception e) {
                    log.error("Помилка імпорту файлу: {}", file, e);
                }

                int processed = processedFiles.incrementAndGet();
                if (progressConsumer != null && totalFiles.get() > 0) {
                    progressConsumer.accept((double) processed / totalFiles.get());
                }
            }
        } catch (IOException e) {
            log.error("Помилка обходу каталогу: {}", directory, e);
            throw new RuntimeException("Помилка обходу каталогу", e);
        }

        log.info("Імпорт каталогу завершено. Оброблено {} файлів, збережено {} книг",
                totalFiles.get(), totalSaved.get());
        return totalSaved.get();
    }

    private int saveBooks(List<Book> books) {
        int saved = 0;
        for (Book book : books) {
            bookCommandRepository.save(book);
            eventPublisher.publishEvent(new BookImportedEvent(book.getId()));
            saved++;
        }
        return saved;
    }
}