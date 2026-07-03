package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverLocator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoverLocatorImpl implements CoverLocator {

    private final ZipArchiveReader archiveReader;

    /**
     * Перевіряє, чи є шлях архівом (за розширенням).
     */
    private boolean isArchivePath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".fb2zip") || lower.endsWith(".fbd");
    }

    @Override
    public Optional<Path> locateCoverFile(BookDto book) {
        if (book == null) return Optional.empty();

        String folder = book.getFolder();
        String fileName = book.getFileName();
        String root = book.getCollectionRoot();

        log.debug("locateCoverFile: folder='{}', fileName='{}', collectionRoot='{}'",
                folder, fileName, root);

        // Якщо немає папки – використовуємо лише fileName
        if (folder == null || folder.isBlank()) {
            if (fileName == null || fileName.isBlank()) {
                return Optional.empty();
            }
            Path path = Paths.get(fileName);
            log.debug("Шлях (без папки): {}", path);
            return Optional.of(path);
        }

        // Якщо папка є архівом – повертаємо її як шлях до файлу
        if (isArchivePath(folder)) {
            Path archivePath;
            if (root != null && !root.isBlank() && !Paths.get(folder).isAbsolute()) {
                archivePath = Paths.get(root, folder);
            } else {
                archivePath = Paths.get(folder);
            }
            log.debug("Шлях до архіву: {}", archivePath);
            return Optional.of(archivePath);
        }

        // Якщо папка не архів – будуємо повний шлях до файлу
        Path folderPath = Paths.get(folder);
        if (folderPath.isAbsolute()) {
            if (fileName != null && !fileName.isBlank()) {
                Path fullPath = folderPath.resolve(fileName);
                log.debug("Абсолютний шлях (папка + файл): {}", fullPath);
                return Optional.of(fullPath);
            } else {
                log.debug("Абсолютний шлях (тільки папка): {}", folderPath);
                return Optional.of(folderPath);
            }
        }

        // Якщо є collectionRoot – додаємо його
        if (root != null && !root.isBlank()) {
            Path resolved = Paths.get(root, folder);
            if (fileName != null && !fileName.isBlank()) {
                resolved = resolved.resolve(fileName);
            }
            log.debug("Шлях з collectionRoot: {} -> {}", root, resolved);
            return Optional.of(resolved);
        }

        // Відносний шлях (без root) – об'єднуємо з fileName
        if (fileName != null && !fileName.isBlank()) {
            Path fullPath = folderPath.resolve(fileName);
            log.debug("Відносний шлях (папка + файл): {}", fullPath);
            return Optional.of(fullPath);
        } else {
            log.debug("Відносний шлях (тільки папка): {}", folderPath);
            return Optional.of(folderPath);
        }
    }

    @Override
    public Optional<String> locateCoverInArchive(BookDto book, Path archivePath) {
        if (book == null || archivePath == null) return Optional.empty();

        log.debug("Пошук обкладинки в архіві: {}, книга: {}", archivePath, book.getTitle());

        // Шукаємо за archiveEntry
        if (book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()) {
            String entry = book.getArchiveEntry();
            if (archiveReader.listEntries(archivePath).contains(entry)) {
                log.debug("Знайдено за archiveEntry: {}", entry);
                return Optional.of(entry);
            }
        }

        // Шукаємо FB2 за назвою файлу книги
        String bookFileName = book.getFileName();
        if (bookFileName != null && !bookFileName.isBlank()) {
            String target = bookFileName.toLowerCase();
            Optional<String> found = archiveReader.listEntries(archivePath).stream()
                    .filter(e -> {
                        String lower = e.toLowerCase();
                        return lower.equals(target) || lower.endsWith("/" + target);
                    })
                    .findFirst();
            if (found.isPresent()) {
                log.debug("Знайдено FB2 за назвою файлу: {}", found.get());
                return found;
            }
        }

        // Перший FB2
        Optional<String> firstFb2 = archiveReader.listEntries(archivePath).stream()
                .filter(e -> e.toLowerCase().endsWith(".fb2"))
                .findFirst();
        if (firstFb2.isPresent()) {
            log.debug("Використовуємо перший FB2: {}", firstFb2.get());
            return firstFb2;
        }

        log.debug("Не знайдено жодного FB2 в архіві");
        return Optional.empty();
    }
}