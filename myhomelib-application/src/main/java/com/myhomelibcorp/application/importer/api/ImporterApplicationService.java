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
import java.util.function.DoubleConsumer;
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

    // Простий метод без прогресу (для сумісності)
    @Transactional
    public int importDirectory(Path directory) {
        return importDirectory(directory, progress -> {}, new AtomicBoolean(false));
    }

    // Основний метод з прогресом
    @Transactional
    public int importDirectory(Path directory, DoubleConsumer progressConsumer, AtomicBoolean cancelFlag) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Шлях не є каталогом: " + directory);
        }

        log.info("Початок імпорту каталогу: {}", directory);

        try (Stream<Path> paths = Files.walk(directory)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            importerRegistry.findImporter(path);
                            return true;
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    })
                    .toList();

            int total = files.size();
            int processed = 0;
            int saved = 0;

            for (Path file : files) {
                if (cancelFlag.get()) {
                    log.info("Імпорт скасовано");
                    break;
                }
                try {
                    saved += importBooks(file);
                } catch (Exception e) {
                    log.error("Помилка імпорту файлу: {}", file, e);
                }
                processed++;
                progressConsumer.accept((double) processed / total);
            }

            log.info("Імпорт каталогу завершено. Збережено {} книг", saved);
            return saved;
        } catch (IOException e) {
            log.error("Помилка обходу каталогу: {}", directory, e);
            throw new RuntimeException("Помилка обходу каталогу", e);
        }
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