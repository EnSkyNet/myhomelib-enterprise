package com.myhomelibcorp.application.imports.scanner;

import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryScanner {

    private final ImporterRegistry importerRegistry;

    /**
     * Сканує каталог і повертає список файлів, які підтримуються імпортерами.
     */
    public List<Path> scan(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Шлях не є каталогом: " + directory);
        }

        log.info("Сканування каталогу: {}", directory);
        try (Stream<Path> pathStream = Files.walk(directory)) {
            List<Path> files = pathStream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        try {
                            importerRegistry.findImporter(path);
                            return true;
                        } catch (IllegalArgumentException e) {
                            return false;
                        }
                    })
                    .collect(Collectors.toList());
            log.info("Знайдено {} файлів для імпорту", files.size());
            return files;
        }
    }

    /**
     * Підраховує кількість підтримуваних файлів у каталозі.
     */
    public long countSupportedFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return 0;
        }
        try (Stream<Path> pathStream = Files.walk(directory)) {
            return pathStream
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
    }
}