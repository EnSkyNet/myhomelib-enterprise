package com.myhomelibcorp.infrastructure.image;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.cover.Cover;
import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
@RequiredArgsConstructor
@Slf4j
public class ArchiveCoverReader {

    private final Fb2CoverParser fb2CoverParser;

    public Image extractImage(BookDto book) {
        if (book == null) {
            log.warn("BookDto is null");
            return null;
        }
        log.debug("Витягуємо обкладинку для '{}', id={}", book.getTitle(), book.getId());

        try {
            Path path = buildPath(book);
            log.debug("Побудований шлях: {}", path);
            if (path == null) {
                log.warn("Шлях null для книги '{}'", book.getTitle());
                return null;
            }
            if (!path.toFile().exists()) {
                log.warn("Файл не існує: {}", path);
                return null;
            }

            String fileName = path.getFileName().toString().toLowerCase();
            boolean isArchive = fileName.endsWith(".zip") || fileName.endsWith(".fb2zip") || fileName.endsWith(".fbd");

            Image result = null;
            if (isArchive) {
                result = extractFromArchive(path, book);
            } else {
                try (InputStream is = java.nio.file.Files.newInputStream(path)) {
                    result = fb2CoverParser.parse(is);
                }
            }
            log.debug("Результат витягування: {}", result != null ? "зображення отримано" : "null");
            return result;
        } catch (Exception e) {
            log.error("Помилка витягування обкладинки для '{}'", book.getTitle(), e);
            return null;
        }
    }

    public Cover extractCoverData(BookDto book) {
        return Cover.empty();
    }

    private Image extractFromArchive(Path archivePath, BookDto book) {
        File file = archivePath.toFile();
        if (!file.exists()) {
            log.warn("Архів не існує: {}", archivePath);
            return null;
        }

        String[] charsets = {"CP866", "Windows-1251", "UTF-8"};

        for (String charsetName : charsets) {
            try (ZipFile zip = new ZipFile(file, Charset.forName(charsetName))) {
                log.debug("Успішно відкрито архів з кодуванням: {}", charsetName);

                List<ZipEntry> fb2Entries = zip.stream()
                        .filter(e -> !e.isDirectory() && e.getName().toLowerCase().endsWith(".fb2"))
                        .collect(Collectors.toList());
                log.debug("Знайдено FB2-файлів в архіві: {}", fb2Entries.size());

                // 1. Якщо вказано archiveEntry
                if (book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()) {
                    ZipEntry entry = zip.getEntry(book.getArchiveEntry());
                    if (entry != null) {
                        log.debug("Спроба отримати обкладинку з archiveEntry: {}", book.getArchiveEntry());
                        Image img = fb2CoverParser.parseFromZipEntry(zip, entry);
                        if (img != null) {
                            log.debug("Обкладинка отримана з archiveEntry");
                            return img;
                        }
                    }
                }

                // 2. Шукаємо FB2 за назвою файлу книги
                String bookFileName = book.getFileName();
                if (bookFileName != null && !bookFileName.isBlank()) {
                    String target = bookFileName.toLowerCase();
                    ZipEntry fb2Entry = fb2Entries.stream()
                            .filter(e -> {
                                String name = e.getName().toLowerCase();
                                return name.equals(target) || name.endsWith("/" + target);
                            })
                            .findFirst()
                            .orElse(null);
                    if (fb2Entry != null) {
                        log.debug("Знайдено FB2 за назвою: {}", fb2Entry.getName());
                        Image img = fb2CoverParser.parseFromZipEntry(zip, fb2Entry);
                        if (img != null) {
                            log.debug("Обкладинка отримана з FB2 за назвою");
                            return img;
                        }
                    } else {
                        log.debug("Не знайдено FB2 за назвою '{}'", target);
                    }
                }

                // 3. Фолбек – перший FB2
                if (!fb2Entries.isEmpty()) {
                    ZipEntry firstFb2 = fb2Entries.get(0);
                    log.debug("Використовуємо перший FB2: {}", firstFb2.getName());
                    Image img = fb2CoverParser.parseFromZipEntry(zip, firstFb2);
                    if (img != null) {
                        log.debug("Обкладинка отримана з першого FB2");
                        return img;
                    }
                }

                // 4. Шукаємо будь-яке зображення (jpg, png)
                Image anyImage = searchAnyImage(zip);
                if (anyImage != null) {
                    log.debug("Обкладинка отримана як окреме зображення");
                    return anyImage;
                }

                log.debug("Обкладинка не знайдена в архіві з кодуванням {}", charsetName);

            } catch (Exception e) {
                log.warn("Не вдалося відкрити з charset {}: {}", charsetName, e.getMessage());
            }
        }
        return null;
    }

    private Image searchAnyImage(ZipFile zip) {
        return zip.stream()
                .filter(e -> !e.isDirectory())
                .filter(e -> {
                    String n = e.getName().toLowerCase();
                    return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif");
                })
                .map(e -> {
                    try {
                        return fb2CoverParser.loadImageFromEntry(zip, e);
                    } catch (Exception ex) {
                        log.trace("Не вдалося завантажити зображення з entry: {}", e.getName(), ex);
                        return null;
                    }
                })
                .filter(img -> img != null)
                .findFirst()
                .orElse(null);
    }

    private Path buildPath(BookDto book) {
        String folder = book.getFolder();
        String fileName = book.getFileName();
        String root = book.getCollectionRoot();

        log.debug("buildPath: folder='{}', fileName='{}', collectionRoot='{}'", folder, fileName, root);

        if (folder == null || folder.isBlank()) {
            return fileName != null ? Paths.get(fileName) : null;
        }

        Path folderPath = Paths.get(folder);
        if (folderPath.isAbsolute()) {
            log.debug("Шлях абсолютний, collectionRoot не використовується");
            return folderPath;
        }

        if (root != null && !root.isBlank()) {
            Path resolved = Paths.get(root, folder);
            log.debug("Використовуємо collectionRoot: {} -> {}", root, resolved);
            return resolved;
        }

        return folderPath;
    }
}