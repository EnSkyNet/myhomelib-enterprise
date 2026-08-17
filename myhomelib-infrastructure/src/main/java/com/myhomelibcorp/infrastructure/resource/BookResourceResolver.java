package com.myhomelibcorp.infrastructure.resource;

import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Єдиний сервіс для роботи з файлами книг.
 * Інкапсулює всю логіку пошуку, читання та роботи з архівами.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookResourceResolver implements BookResourcePort {

    private final ZipArchiveReader archiveReader;

    private static final List<String> ARCHIVE_EXTENSIONS = List.of(".zip", ".fb2zip", ".fbd");

    // ==================== Пошук файлів ====================

    @Override
    public Optional<Path> locateBookFile(Book book) {
        if (book == null) {
            return Optional.empty();
        }

        String fileName = book.getFileName();
        String folder = book.getFolder();
        String collectionRoot = book.getCollectionRoot();
        String archiveEntry = book.getArchiveEntry();

        return locateBookFile(fileName, folder, collectionRoot, archiveEntry);
    }

    @Override
    public Optional<Path> locateBookFile(String fileName, String folder, String collectionRoot, String archiveEntry) {
        log.debug("locateBookFile: fileName='{}', folder='{}', root='{}', archiveEntry='{}'",
                fileName, folder, collectionRoot, archiveEntry);

        // 1. Якщо є archiveEntry, шукаємо архів
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            Path archivePath = findArchivePath(fileName, folder, collectionRoot);
            if (archivePath != null && Files.exists(archivePath)) {
                log.debug("Знайдено архів: {}", archivePath);
                return Optional.of(archivePath);
            }
        }

        // 2. Якщо fileName є архівом
        if (fileName != null && isArchive(fileName)) {
            Path archivePath = buildFilePath(collectionRoot, folder, fileName);
            if (archivePath != null && Files.exists(archivePath)) {
                log.debug("Знайдено архів (fileName): {}", archivePath);
                return Optional.of(archivePath);
            }
        }

        // 3. Якщо folder є архівом
        if (folder != null && isArchive(folder)) {
            Path archivePath = buildFilePath(collectionRoot, null, folder);
            if (archivePath != null && Files.exists(archivePath)) {
                log.debug("Знайдено архів (folder): {}", archivePath);
                return Optional.of(archivePath);
            }
        }

        // 4. Звичайний файл
        Path filePath = buildFilePath(collectionRoot, folder, fileName);
        if (filePath != null && Files.exists(filePath) && !isArchive(filePath.toString())) {
            log.debug("Знайдено файл: {}", filePath);
            return Optional.of(filePath);
        }

        log.debug("Файл не знайдено: fileName='{}', folder='{}'", fileName, folder);
        return Optional.empty();
    }

    private Path findArchivePath(String fileName, String folder, String collectionRoot) {
        // Спроба 1: folder як архів
        if (folder != null && !folder.isBlank() && isArchive(folder)) {
            return buildFilePath(collectionRoot, null, folder);
        }

        // Спроба 2: fileName як архів
        if (fileName != null && !fileName.isBlank() && isArchive(fileName)) {
            return buildFilePath(collectionRoot, folder, fileName);
        }

        // Спроба 3: folder + fileName як архів
        if (folder != null && !folder.isBlank() && fileName != null && !fileName.isBlank()) {
            Path combined = buildFilePath(collectionRoot, folder, fileName);
            if (combined != null && Files.exists(combined) && isArchive(combined.toString())) {
                return combined;
            }
        }

        return null;
    }

    // ==================== Читання даних ====================

    @Override
    public Optional<InputStream> readBookData(Book book) {
        if (book == null) {
            return Optional.empty();
        }

        String fileName = book.getFileName();
        String folder = book.getFolder();
        String collectionRoot = book.getCollectionRoot();
        String archiveEntry = book.getArchiveEntry();

        return readBookData(fileName, folder, collectionRoot, archiveEntry);
    }

    @Override
    public Optional<InputStream> readBookData(String fileName, String folder, String collectionRoot, String archiveEntry) {
        try {
            // 1. Якщо є archiveEntry - читаємо з архіву
            if (archiveEntry != null && !archiveEntry.isBlank()) {
                Path archivePath = findArchivePath(fileName, folder, collectionRoot);
                if (archivePath != null && Files.exists(archivePath)) {
                    log.debug("Читання з архіву: {}, запис: {}", archivePath, archiveEntry);
                    return archiveReader.readEntry(archivePath, archiveEntry);
                }
            }

            // 2. Якщо fileName або folder є архівом
            Path archivePath = findArchivePath(fileName, folder, collectionRoot);
            if (archivePath != null && Files.exists(archivePath)) {
                // Шукаємо перший FB2 в архіві
                log.debug("Пошук FB2 в архіві: {}", archivePath);
                return archiveReader.findFirstEntry(archivePath,
                        e -> e.toLowerCase().endsWith(".fb2") || e.toLowerCase().endsWith(".fbd"));
            }

            // 3. Звичайний файл
            Path filePath = buildFilePath(collectionRoot, folder, fileName);
            if (filePath != null && Files.exists(filePath)) {
                log.debug("Читання файлу: {}", filePath);
                return Optional.of(Files.newInputStream(filePath));
            }

            log.warn("Не вдалося знайти файл для читання: fileName='{}', folder='{}'", fileName, folder);
            return Optional.empty();

        } catch (Exception e) {
            log.error("Помилка читання даних книги: fileName='{}', folder='{}'", fileName, folder, e);
            return Optional.empty();
        }
    }

    // ==================== Робота з архівами ====================

    @Override
    public boolean isArchive(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase();
        return ARCHIVE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    @Override
    public List<String> listArchiveEntries(Path archivePath) {
        return archiveReader.listEntries(archivePath);
    }

    @Override
    public Optional<InputStream> readArchiveEntry(Path archivePath, String entryName) {
        return archiveReader.readEntry(archivePath, entryName);
    }

    @Override
    public Optional<InputStream> findFirstArchiveEntry(Path archivePath, Predicate<String> filter) {
        return archiveReader.findFirstEntry(archivePath, filter);
    }

    // ==================== Побудова шляхів ====================

    @Override
    public Path buildFilePath(String root, String folder, String fileName) {
        if (fileName != null && !fileName.isBlank()) {
            Path fileNamePath = Paths.get(fileName);
            if (fileNamePath.isAbsolute()) {
                return fileNamePath;
            }
        }

        if (folder != null && !folder.isBlank()) {
            Path folderPath = Paths.get(folder);
            if (folderPath.isAbsolute()) {
                if (fileName != null && !fileName.isBlank()) {
                    return folderPath.resolve(fileName);
                }
                return folderPath;
            }
        }

        if (root != null && !root.isBlank() && folder != null && !folder.isBlank()) {
            Path rootPath = Paths.get(root);
            Path folderPath = Paths.get(folder);
            if (fileName != null && !fileName.isBlank()) {
                return rootPath.resolve(folderPath).resolve(fileName);
            }
            return rootPath.resolve(folderPath);
        }

        if (root != null && !root.isBlank() && fileName != null && !fileName.isBlank()) {
            return Paths.get(root).resolve(fileName);
        }

        if (fileName != null && !fileName.isBlank()) {
            return Paths.get(fileName);
        }

        if (folder != null && !folder.isBlank()) {
            return Paths.get(folder);
        }

        return Paths.get(".");
    }
}