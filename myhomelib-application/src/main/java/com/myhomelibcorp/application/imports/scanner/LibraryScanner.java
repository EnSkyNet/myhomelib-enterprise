package com.myhomelibcorp.application.imports.scanner;

import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.util.SafeFilePublication;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
@Slf4j
public class LibraryScanner {

    private final ImporterRegistry importerRegistry;

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
        Path safeRoot;
        try {
            safeRoot = SafeFilePublication.requireSafeDirectoryPath(directory, "Library scan root");
        } catch (IOException unsafeRoot) {
            throw new IllegalArgumentException("Небезпечний або недоступний каталог: " + directory, unsafeRoot);
        }
        int depth = includeSubfolders
                ? Math.max(1, maxDepth <= 0 ? Integer.MAX_VALUE : maxDepth)
                : 1;
        long sizeLimit = maxFileSize <= 0 ? Long.MAX_VALUE : maxFileSize;

        log.info("Потокове сканування каталогу: {} (depth={}, maxFileSize={})",
                safeRoot, depth == Integer.MAX_VALUE ? "unbounded" : depth, sizeLimit);

        return Files.walk(safeRoot, depth)
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
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
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && attributes.size() <= maxFileSize;
        } catch (IOException e) {
            log.warn("Не вдалося прочитати розмір файлу {}: {}", path, e.getMessage());
            return false;
        }
    }
}
