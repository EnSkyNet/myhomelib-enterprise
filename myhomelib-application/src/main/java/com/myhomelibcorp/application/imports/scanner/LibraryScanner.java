package com.myhomelibcorp.application.imports.scanner;

import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryScanner {

    private final ImporterRegistry importerRegistry;

    /**
     * Compatibility API for callers that explicitly need a materialized list.
     * Folder synchronization uses the streaming API below to remain bounded.
     */
    public List<Path> scan(Path directory) throws IOException {
        try (Stream<Path> paths = streamSupportedFiles(directory)) {
            return paths.toList();
        }
    }

    public long countSupportedFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return 0;
        try (Stream<Path> paths = streamSupportedFiles(directory)) {
            return paths.count();
        }
    }

    /**
     * Streams supported files. The caller must close the stream.
     */
    public Stream<Path> streamSupportedFiles(Path directory) throws IOException {
        return streamSupportedFiles(directory, true, Integer.MAX_VALUE, Long.MAX_VALUE);
    }

    /**
     * Memory-bounded filesystem walk honoring synchronization options.
     * maxDepth is counted below the selected root (1 means files directly in root).
     */
    public Stream<Path> streamSupportedFiles(Path directory,
                                             boolean includeSubfolders,
                                             int maxDepth,
                                             long maxFileSize) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("Шлях не є каталогом: " + directory);
        }
        int depth = includeSubfolders
                ? Math.max(1, maxDepth <= 0 ? Integer.MAX_VALUE : maxDepth)
                : 1;
        long sizeLimit = maxFileSize <= 0 ? Long.MAX_VALUE : maxFileSize;

        log.info("Потокове сканування каталогу: {} (depth={}, maxFileSize={})",
                directory, depth == Integer.MAX_VALUE ? "unbounded" : depth, sizeLimit);

        return Files.walk(directory, depth)
                .filter(Files::isRegularFile)
                .filter(path -> withinSize(path, sizeLimit))
                .filter(this::supported);
    }

    private boolean supported(Path path) {
        try {
            importerRegistry.findImporter(path);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private boolean withinSize(Path path, long maxFileSize) {
        try {
            return Files.size(path) <= maxFileSize;
        } catch (IOException e) {
            log.warn("Не вдалося прочитати розмір файлу {}: {}", path, e.getMessage());
            return false;
        }
    }
}
