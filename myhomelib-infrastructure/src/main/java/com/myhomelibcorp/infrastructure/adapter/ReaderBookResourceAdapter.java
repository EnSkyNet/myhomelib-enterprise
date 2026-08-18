package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.resource.ReaderBookResourcePort;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import com.myhomelibcorp.infrastructure.resource.BookResourceResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Адаптер для роботи з ресурсами книг.
 * Реалізує порт ReaderBookResourcePort.
 * Використовує BookResourceResolver для основної логіки.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ReaderBookResourceAdapter implements ReaderBookResourcePort {

    private final BookResourceResolver bookResourceResolver;
    private final ZipArchiveReader archiveReader;

    private static final List<String> ARCHIVE_EXTENSIONS = List.of(".zip", ".fb2zip", ".fbd");

    @Override
    public Optional<InputStream> readBookData(BookDto bookDto) {
        if (bookDto == null) {
            return Optional.empty();
        }

        String fileName = bookDto.getFileName();
        String folder = bookDto.getFolder();
        String collectionRoot = bookDto.getCollectionRoot();
        String archiveEntry = bookDto.getArchiveEntry();

        return readBookData(fileName, folder, collectionRoot, archiveEntry);
    }

    @Override
    public Optional<InputStream> readBookData(String fileName, String folder, String collectionRoot, String archiveEntry) {
        log.debug("readBookData: fileName='{}', folder='{}', root='{}', archiveEntry='{}'",
                fileName, folder, collectionRoot, archiveEntry);

        try {
            // 1. Якщо є archiveEntry - читаємо з архіву
            if (archiveEntry != null && !archiveEntry.isBlank()) {
                Optional<Path> archivePath = locateArchivePath(fileName, folder, collectionRoot);
                if (archivePath.isPresent() && Files.exists(archivePath.get())) {
                    log.debug("Читання з архіву: {}, запис: {}", archivePath.get(), archiveEntry);
                    return archiveReader.readEntry(archivePath.get(), archiveEntry);
                }
            }

            // 2. Якщо fileName або folder є архівом
            Optional<Path> archivePath = locateArchivePath(fileName, folder, collectionRoot);
            if (archivePath.isPresent() && Files.exists(archivePath.get())) {
                log.debug("Пошук FB2 в архіві: {}", archivePath.get());
                return archiveReader.findFirstEntry(archivePath.get(),
                        e -> e.toLowerCase().endsWith(".fb2") || e.toLowerCase().endsWith(".fbd"));
            }

            // 3. Звичайний файл
            Optional<Path> filePath = locateBookFile(fileName, folder, collectionRoot, archiveEntry);
            if (filePath.isPresent() && Files.exists(filePath.get())) {
                log.debug("Читання файлу: {}", filePath.get());
                return Optional.of(Files.newInputStream(filePath.get()));
            }

            log.warn("Не вдалося знайти файл для читання: fileName='{}', folder='{}'", fileName, folder);
            return Optional.empty();

        } catch (Exception e) {
            log.error("Помилка читання даних книги: fileName='{}', folder='{}'", fileName, folder, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<Path> locateBookFile(BookDto bookDto) {
        if (bookDto == null) {
            return Optional.empty();
        }

        String fileName = bookDto.getFileName();
        String folder = bookDto.getFolder();
        String collectionRoot = bookDto.getCollectionRoot();
        String archiveEntry = bookDto.getArchiveEntry();

        return locateBookFile(fileName, folder, collectionRoot, archiveEntry);
    }

    @Override
    public Optional<Path> locateBookFile(String fileName, String folder, String collectionRoot, String archiveEntry) {
        log.debug("locateBookFile: fileName='{}', folder='{}', root='{}', archiveEntry='{}'",
                fileName, folder, collectionRoot, archiveEntry);

        // 1. Якщо є archiveEntry, шукаємо архів
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            Optional<Path> archivePath = locateArchivePath(fileName, folder, collectionRoot);
            if (archivePath.isPresent() && Files.exists(archivePath.get())) {
                log.debug("Знайдено архів: {}", archivePath.get());
                return archivePath;
            }
        }

        // 2. Якщо fileName є архівом
        if (fileName != null && isArchive(fileName)) {
            Path archivePath = bookResourceResolver.buildFilePath(collectionRoot, folder, fileName);
            if (archivePath != null && Files.exists(archivePath)) {
                log.debug("Знайдено архів (fileName): {}", archivePath);
                return Optional.of(archivePath);
            }
        }

        // 3. Якщо folder є архівом
        if (folder != null && isArchive(folder)) {
            Path archivePath = bookResourceResolver.buildFilePath(collectionRoot, null, folder);
            if (archivePath != null && Files.exists(archivePath)) {
                log.debug("Знайдено архів (folder): {}", archivePath);
                return Optional.of(archivePath);
            }
        }

        // 4. Звичайний файл
        Path filePath = bookResourceResolver.buildFilePath(collectionRoot, folder, fileName);
        if (filePath != null && Files.exists(filePath) && !isArchive(filePath.toString())) {
            log.debug("Знайдено файл: {}", filePath);
            return Optional.of(filePath);
        }

        log.debug("Файл не знайдено: fileName='{}', folder='{}'", fileName, folder);
        return Optional.empty();
    }

    @Override
    public boolean isArchive(String path) {
        if (path == null) {
            return false;
        }
        String lower = path.toLowerCase();
        return ARCHIVE_EXTENSIONS.stream().anyMatch(lower::endsWith);
    }

    @Override
    public Path buildFilePath(String root, String folder, String fileName) {
        return bookResourceResolver.buildFilePath(root, folder, fileName);
    }

    /**
     * Знаходить шлях до архіву.
     */
    private Optional<Path> locateArchivePath(String fileName, String folder, String collectionRoot) {
        // Спроба 1: folder як архів
        if (folder != null && !folder.isBlank() && isArchive(folder)) {
            Path path = bookResourceResolver.buildFilePath(collectionRoot, null, folder);
            if (path != null && Files.exists(path)) {
                return Optional.of(path);
            }
        }

        // Спроба 2: fileName як архів
        if (fileName != null && !fileName.isBlank() && isArchive(fileName)) {
            Path path = bookResourceResolver.buildFilePath(collectionRoot, folder, fileName);
            if (path != null && Files.exists(path)) {
                return Optional.of(path);
            }
        }

        // Спроба 3: folder + fileName як архів
        if (folder != null && !folder.isBlank() && fileName != null && !fileName.isBlank()) {
            Path path = bookResourceResolver.buildFilePath(collectionRoot, folder, fileName);
            if (path != null && Files.exists(path) && isArchive(path.toString())) {
                return Optional.of(path);
            }
        }

        return Optional.empty();
    }
}