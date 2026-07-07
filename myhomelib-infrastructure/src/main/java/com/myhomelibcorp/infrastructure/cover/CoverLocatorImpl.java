package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverLocator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoverLocatorImpl implements CoverLocator {

    private final ZipArchiveReader archiveReader;

    private boolean isArchivePath(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        return lower.endsWith(".zip") || lower.endsWith(".fb2zip") || lower.endsWith(".fbd");
    }

    // Нормалізація: приводимо до нижнього регістру, видаляємо пробіли, дефіси, підкреслення,
    // залишаємо тільки букви, цифри та крапку
    private final Function<String, String> normalize = s -> {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("[\\s_\\-]+", "")
                .replaceAll("[^a-zа-я0-9.]", "");
    };

    @Override
    public Optional<Path> locateCoverFile(BookDto book) {
        if (book == null) return Optional.empty();

        String folder = book.getFolder();
        String fileName = book.getFileName();
        String root = book.getCollectionRoot();

        log.debug("locateCoverFile: folder='{}', fileName='{}', collectionRoot='{}'",
                folder, fileName, root);

        if (folder == null || folder.isBlank()) {
            if (fileName == null || fileName.isBlank()) {
                return Optional.empty();
            }
            Path path = Paths.get(fileName);
            log.debug("Шлях (без папки): {}", path);
            return Optional.of(path);
        }

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

        if (root != null && !root.isBlank()) {
            Path resolved = Paths.get(root, folder);
            if (fileName != null && !fileName.isBlank()) {
                resolved = resolved.resolve(fileName);
            }
            log.debug("Шлях з collectionRoot: {} -> {}", root, resolved);
            return Optional.of(resolved);
        }

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

        List<String> entries = archiveReader.listEntries(archivePath);
        if (entries.isEmpty()) {
            log.warn("Архів порожній або не вдалося прочитати: {}", archivePath);
            return Optional.empty();
        }

        log.info("Знайдено {} записів у архіві, перші 5: {}", entries.size(),
                entries.stream().limit(5).collect(Collectors.toList()));

        String archiveEntry = book.getArchiveEntry();
        String fileName = book.getFileName();
        String title = book.getTitle();

        // 1. Пошук за archiveEntry (з нормалізацією)
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            log.debug("Шукаємо за archiveEntry: '{}'", archiveEntry);
            String normalizedArchive = normalize.apply(archiveEntry);
            for (String entry : entries) {
                if (normalize.apply(entry).equals(normalizedArchive)) {
                    log.debug("Знайдено за нормалізованим archiveEntry: {}", entry);
                    return Optional.of(entry);
                }
            }
            // Якщо не знайшли, пробуємо шукати за ім'ям файлу (без шляху) з archiveEntry
            String fileNameFromEntry = Paths.get(archiveEntry).getFileName().toString();
            String normalizedFileName = normalize.apply(fileNameFromEntry);
            for (String entry : entries) {
                if (normalize.apply(entry).contains(normalizedFileName)) {
                    log.debug("Знайдено за ім'ям файлу з archiveEntry: {}", entry);
                    return Optional.of(entry);
                }
            }
        }

        // 2. Пошук за fileName (з нормалізацією)
        if (fileName != null && !fileName.isBlank()) {
            log.debug("Шукаємо за fileName: '{}'", fileName);
            String normalizedFileName = normalize.apply(fileName);
            for (String entry : entries) {
                if (normalize.apply(entry).contains(normalizedFileName)) {
                    log.debug("Знайдено за нормалізованим fileName: {}", entry);
                    return Optional.of(entry);
                }
            }
            // Без розширення
            String baseName = fileName.replaceFirst("\\.[^.]+$", "");
            if (!baseName.equals(fileName)) {
                String normalizedBase = normalize.apply(baseName);
                for (String entry : entries) {
                    if (normalize.apply(entry).contains(normalizedBase)) {
                        log.debug("Знайдено за базовою назвою: {}", entry);
                        return Optional.of(entry);
                    }
                }
            }
        }

        // 3. Пошук за назвою книги (з нормалізацією)
        if (title != null && !title.isBlank()) {
            String normalizedTitle = normalize.apply(title);
            log.debug("Шукаємо за назвою книги: '{}'", normalizedTitle);
            for (String entry : entries) {
                if (normalize.apply(entry).contains(normalizedTitle)) {
                    log.debug("Знайдено за назвою книги: {}", entry);
                    return Optional.of(entry);
                }
            }
        }

        // 4. Перший FB2 (як крайній випадок)
        Optional<String> firstFb2 = entries.stream()
                .filter(e -> e.toLowerCase().endsWith(".fb2"))
                .findFirst();
        if (firstFb2.isPresent()) {
            log.warn("Не вдалося знайти точний FB2 для '{}', використовуємо перший: {}", title, firstFb2.get());
            return firstFb2;
        }

        log.debug("Не знайдено жодного FB2 в архіві");
        return Optional.empty();
    }
}