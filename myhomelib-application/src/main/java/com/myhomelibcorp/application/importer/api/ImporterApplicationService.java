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
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * Універсальний метод імпорту одного файлу.
     */
    @Transactional
    public int importBooks(Path file) {
        log.info("Початок імпорту файлу: {}", file);
        var importer = importerRegistry.findImporter(file);
        List<Book> books = importer.importBooks(file);
        int saved = saveBooks(books);
        log.info("Імпорт завершено. Збережено {} книг", saved);
        return saved;
    }

    /**
     * Рекурсивний імпорт усіх книг з каталогу (і підкаталогів).
     * @param directory кореневий каталог для пошуку
     * @return кількість імпортованих книг
     */
    @Transactional
    public int importDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Шлях не є каталогом: " + directory);
        }

        log.info("Початок імпорту каталогу: {}", directory);
        AtomicInteger totalSaved = new AtomicInteger(0);

        try (Stream<Path> paths = Files.walk(directory)) {
            paths
                    .filter(Files::isRegularFile)
                    .filter(path -> importerRegistry.findImporter(path) != null) // перевіряємо, чи є імпортер
                    .forEach(path -> {
                        try {
                            int saved = importBooks(path);
                            totalSaved.addAndGet(saved);
                        } catch (Exception e) {
                            log.error("Помилка імпорту файлу: {}", path, e);
                        }
                    });
        } catch (IOException e) {
            log.error("Помилка обходу каталогу: {}", directory, e);
            throw new RuntimeException("Помилка обходу каталогу", e);
        }

        log.info("Імпорт каталогу завершено. Збережено {} книг", totalSaved.get());
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