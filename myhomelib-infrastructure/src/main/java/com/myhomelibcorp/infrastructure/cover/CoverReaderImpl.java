package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverReader;
import com.myhomelibcorp.infrastructure.image.Fb2CoverParser;
import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoverReaderImpl implements CoverReader {

    private final Fb2CoverParser fb2CoverParser;
    private final CoverLocatorImpl coverLocator;
    private final ZipArchiveReader archiveReader;

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
                Optional<String> entryName = coverLocator.locateCoverInArchive(book, path);
                if (entryName.isPresent()) {
                    log.info("Знайдено запис у архіві: {}", entryName.get());
                    Optional<InputStream> entryStream = archiveReader.readEntry(path, entryName.get());
                    if (entryStream.isPresent()) {
                        try (InputStream is = entryStream.get()) {
                            Image image = fb2CoverParser.parse(is);
                            if (image != null) {
                                log.info("Обкладинку завантажено з архіву (FB2)");
                                return image;
                            } else {
                                log.warn("fb2CoverParser.parse повернув null");
                            }
                        }
                    } else {
                        log.warn("Не вдалося отримати InputStream для запису: {}", entryName.get());
                    }
                } else {
                    log.warn("Не знайдено жодного запису для обкладинки в архіві");
                }

                // Шукаємо зображення
                log.info("Шукаємо зображення в архіві");
                Optional<InputStream> imageStream = archiveReader.findFirstEntry(path,
                        e -> e.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif)$"));
                if (imageStream.isPresent()) {
                    try (InputStream is = imageStream.get()) {
                        Image image = fb2CoverParser.parseImageOnly(is);
                        if (image != null) {
                            log.info("Обкладинку завантажено з архіву (зображення)");
                            return image;
                        }
                    }
                }
                log.warn("Не знайдено обкладинку в архіві");
                return null;
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
}