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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ImporterApplicationService implements ImportInpxUseCase {

    private static final int BATCH_SIZE = 500;

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
        try (Stream<Book> bookStream = importer.importBooks(file)) {
            return saveBooksBatch(bookStream);
        } catch (Exception e) {
            log.error("Помилка імпорту файлу: {}", file, e);
            throw new RuntimeException("Помилка імпорту", e);
        }
    }

    @Transactional
    public int importDirectory(Path directory) {
        return importDirectory(directory, progress -> {}, new AtomicBoolean(false));
    }

    @Transactional
    public int importDirectory(Path directory, DoubleConsumer progressConsumer, AtomicBoolean cancelFlag) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Шлях не є каталогом: " + directory);
        }

        log.info("Початок імпорту каталогу: {}", directory);

        // ВИПРАВЛЕНО: потокова обробка БЕЗ збору всіх файлів у List
        try (Stream<Path> pathStream = Files.walk(directory)) {
            AtomicInteger processed = new AtomicInteger(0);
            AtomicInteger saved = new AtomicInteger(0);

            // Підрахунок загальної кількості підтримуваних файлів (для прогресу)
            long total;
            try (Stream<Path> countStream = Files.walk(directory)) {
                total = countStream
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            try {
                                importerRegistry.findImporter(path);
                                return true;
                            } catch (IllegalArgumentException e) {
                                return false;
                            }
                        })
                        .count();
            }

            // Другий прохід – власне обробка
            try (Stream<Path> processStream = Files.walk(directory)) {
                processStream
                        .filter(Files::isRegularFile)
                        .filter(path -> {
                            try {
                                importerRegistry.findImporter(path);
                                return true;
                            } catch (IllegalArgumentException e) {
                                return false;
                            }
                        })
                        .forEach(file -> {
                            if (cancelFlag.get()) {
                                log.info("Імпорт скасовано");
                                return;
                            }
                            try {
                                saved.addAndGet(importBooks(file));
                            } catch (Exception e) {
                                log.error("Помилка імпорту файлу: {}", file, e);
                            }
                            int processedCount = processed.incrementAndGet();
                            if (total > 0) {
                                progressConsumer.accept((double) processedCount / total);
                            }
                        });
            }

            log.info("Імпорт каталогу завершено. Збережено {} книг", saved.get());
            return saved.get();

        } catch (IOException e) {
            log.error("Помилка обходу каталогу: {}", directory, e);
            throw new RuntimeException("Помилка обходу каталогу", e);
        }
    }

    private int saveBooksBatch(Stream<Book> bookStream) {
        List<Book> batch = new ArrayList<>(BATCH_SIZE);
        int totalSaved = 0;
        int batchCounter = 0;

        try (Stream<Book> stream = bookStream) {
            Iterator<Book> iterator = stream.iterator();
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