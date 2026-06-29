package com.myhomelibcorp.application.usecase.imports;

import com.myhomelibcorp.application.port.out.ImporterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportDirectoryUseCase {

    private final ImportFileUseCase importFileUseCase;
    private final ImporterRegistry importerRegistry;

    public int execute(Path directory, DoubleConsumer progressConsumer, AtomicBoolean cancelFlag) {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Шлях не є каталогом: " + directory);
        }

        log.info("Початок імпорту каталогу: {}", directory);

        try (Stream<Path> pathStream = Files.walk(directory)) {
            AtomicInteger processed = new AtomicInteger(0);
            AtomicInteger saved = new AtomicInteger(0);

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
                                saved.addAndGet(importFileUseCase.execute(file));
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
}