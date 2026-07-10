// Файл: myhomelib-infrastructure/src/main/java/com/myhomelibcorp/infrastructure/cover/CoverReaderImpl.java
// (додано логування – повний код)
package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverLocator;
import com.myhomelibcorp.application.port.out.cover.CoverReader;
import com.myhomelibcorp.infrastructure.cache.CoverCache;
import com.myhomelibcorp.infrastructure.image.Fb2CoverParser;
import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoverReaderImpl implements CoverReader {

    private final CoverLocator coverLocator;
    private final ZipArchiveReader archiveReader;
    private final Fb2CoverParser fb2CoverParser;
    private final CoverCache coverCache;

    @Override
    public Image readCover(BookDto book) {
        log.info("readCover: початок для книги: {}", book != null ? book.getTitle() : "null");
        if (book == null) {
            log.warn("book == null");
            return null;
        }

        log.debug("BookDto: id={}, title={}, folder={}, fileName={}, collectionRoot={}, archiveEntry={}",
                book.getId(), book.getTitle(), book.getFolder(),
                book.getFileName(), book.getCollectionRoot(), book.getArchiveEntry());

        try {
            Optional<Path> filePath = coverLocator.locateCoverFile(book);
            if (filePath.isEmpty()) {
                log.warn("locateCoverFile повернув empty");
                return null;
            }
            Path path = filePath.get();
            log.info("Шлях до файлу: {}", path);

            if (!Files.exists(path)) {
                log.warn("Файл не існує: {}", path);
                return null;
            }

            if (archiveReader.isArchive(path)) {
                log.info("Файл є архівом: {}", path);
                return extractFromArchive(path, book);
            } else {
                log.info("Читаємо звичайний FB2 файл: {}", path);
                try (InputStream is = Files.newInputStream(path)) {
                    Image image = fb2CoverParser.parse(is);
                    if (image != null) {
                        log.info("Обкладинку завантажено з FB2 файлу");
                        return image;
                    } else {
                        log.warn("fb2CoverParser.parse повернув null для файлу: {}", path);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Помилка читання обкладинки", e);
        }
        log.warn("readCover завершився без результату");
        return null;
    }

    private Image extractFromArchive(Path archivePath, BookDto book) {
        List<String> entries = archiveReader.listEntries(archivePath);
        if (entries.isEmpty()) {
            log.warn("Архів порожній або не вдалося прочитати: {}", archivePath);
            return null;
        }

        log.debug("Знайдено {} записів у архіві", entries.size());

        String archiveEntry = book.getArchiveEntry();
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            log.debug("Шукаємо за archiveEntry: {}", archiveEntry);
            if (entries.contains(archiveEntry)) {
                log.info("Знайдено запис за archiveEntry: {}", archiveEntry);
                return readAndParseEntry(archivePath, archiveEntry);
            }
            String simpleName = Paths.get(archiveEntry).getFileName().toString();
            if (!simpleName.equals(archiveEntry)) {
                Optional<String> found = entries.stream()
                        .filter(e -> e.equals(simpleName) || e.endsWith("/" + simpleName))
                        .findFirst();
                if (found.isPresent()) {
                    log.info("Знайдено запис за ім'ям файлу з archiveEntry: {}", found.get());
                    return readAndParseEntry(archivePath, found.get());
                }
            }
        }

        String fileName = book.getFileName();
        if (fileName != null && !fileName.isBlank()) {
            log.debug("Шукаємо за fileName: {}", fileName);
            Optional<String> found = entries.stream()
                    .filter(e -> e.equals(fileName) || e.endsWith("/" + fileName))
                    .findFirst();
            if (found.isPresent()) {
                log.info("Знайдено запис за fileName: {}", found.get());
                return readAndParseEntry(archivePath, found.get());
            }
        }

        String title = book.getTitle();
        if (title != null && !title.isBlank()) {
            log.debug("Шукаємо за назвою книги: {}", title);
            String normalizedTitle = title.toLowerCase()
                    .replaceAll("[\\s_\\-]+", "")
                    .replaceAll("[^a-zа-я0-9]", "");
            Optional<String> found = entries.stream()
                    .filter(e -> {
                        String name = Paths.get(e).getFileName().toString();
                        String normalizedName = name.toLowerCase()
                                .replaceAll("[\\s_\\-]+", "")
                                .replaceAll("[^a-zа-я0-9]", "");
                        return normalizedName.contains(normalizedTitle);
                    })
                    .findFirst();
            if (found.isPresent()) {
                log.info("Знайдено запис за назвою книги: {}", found.get());
                return readAndParseEntry(archivePath, found.get());
            }
        }

        log.info("Не знайдено конкретного FB2, використовуємо перший FB2");
        Optional<String> firstFb2 = entries.stream()
                .filter(e -> e.toLowerCase().endsWith(".fb2"))
                .findFirst();
        if (firstFb2.isPresent()) {
            log.warn("Використовуємо перший FB2: {}", firstFb2.get());
            return readAndParseEntry(archivePath, firstFb2.get());
        }

        log.info("Шукаємо будь-яке зображення в архіві");
        Optional<InputStream> imageStream = archiveReader.findFirstEntry(archivePath,
                e -> e.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif)$"));
        if (imageStream.isPresent()) {
            try (InputStream is = imageStream.get()) {
                Image image = fb2CoverParser.parseImageOnly(is);
                if (image != null) {
                    log.info("Обкладинку завантажено з архіву (зображення)");
                    return image;
                }
            } catch (Exception e) {
                log.error("Помилка читання зображення з архіву", e);
            }
        }

        log.warn("Не знайдено обкладинку в архіві");
        return null;
    }

    private Image readAndParseEntry(Path archivePath, String entryName) {
        Optional<InputStream> entryStream = archiveReader.readEntry(archivePath, entryName);
        if (entryStream.isPresent()) {
            try (InputStream is = entryStream.get()) {
                Image image = fb2CoverParser.parse(is);
                if (image != null) {
                    log.info("Обкладинку завантажено з запису: {}", entryName);
                    return image;
                } else {
                    log.warn("fb2CoverParser.parse повернув null для запису: {}", entryName);
                }
            } catch (Exception e) {
                log.error("Помилка парсингу запису: {}", entryName, e);
            }
        } else {
            log.warn("Не вдалося прочитати запис: {}", entryName);
        }
        return null;
    }
}