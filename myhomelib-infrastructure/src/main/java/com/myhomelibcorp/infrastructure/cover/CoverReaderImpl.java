package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverLocator;
import com.myhomelibcorp.application.port.out.cover.CoverReader;
import com.myhomelibcorp.infrastructure.image.Fb2CoverParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoverReaderImpl implements CoverReader {

    private final CoverLocator coverLocator;
    private final ZipArchiveReader archiveReader;
    private final Fb2CoverParser fb2CoverParser;

    @Override
    public byte[] readCover(BookDto book) {
        if (book == null) {
            log.warn("book == null");
            return null;
        }

        log.debug("readCover для книги: id={}, title={}", book.getId(), book.getTitle());

        try {
            Path filePath = coverLocator.locateCoverFile(book).orElse(null);
            if (filePath == null || !Files.exists(filePath)) {
                log.debug("Файл не знайдено: {}", filePath);
                return null;
            }

            if (archiveReader.isArchive(filePath)) {
                log.debug("Читаємо обкладинку з архіву: {}", filePath);
                return extractFromArchive(filePath, book);
            } else {
                log.debug("Читаємо звичайний FB2 файл: {}", filePath);
                try (InputStream is = Files.newInputStream(filePath)) {
                    return fb2CoverParser.parseToBytes(is);
                }
            }
        } catch (Exception e) {
            log.error("Помилка читання обкладинки для книги {}", book.getId(), e);
            return null;
        }
    }

    private byte[] extractFromArchive(Path archivePath, BookDto book) {
        List<String> entries = archiveReader.listEntries(archivePath);
        if (entries.isEmpty()) {
            log.warn("Архів порожній або не вдалося прочитати: {}", archivePath);
            return null;
        }

        String archiveEntry = book.getArchiveEntry();
        String fileName = book.getFileName();
        String title = book.getTitle();

        // Спроба знайти потрібний запис
        String targetEntry = findBestEntry(entries, archiveEntry, fileName, title);
        if (targetEntry != null) {
            try (InputStream is = archiveReader.readEntry(archivePath, targetEntry).orElse(null)) {
                if (is != null) {
                    return fb2CoverParser.parseToBytes(is);
                }
            } catch (Exception e) {
                log.error("Помилка читання запису {} з архіву", targetEntry, e);
            }
        }

        // Якщо не знайшли FB2 — шукаємо будь-яке зображення
        try (InputStream imageStream = archiveReader.findFirstEntry(archivePath,
                e -> e.toLowerCase().matches(".*\\.(jpg|jpeg|png|gif)$")).orElse(null)) {
            if (imageStream != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                imageStream.transferTo(baos);
                return baos.toByteArray();
            }
        } catch (Exception e) {
            log.error("Помилка читання зображення з архіву", e);
        }

        return null;
    }

    private String findBestEntry(List<String> entries, String archiveEntry, String fileName, String title) {
        // 1. Точний збіг archiveEntry
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            for (String e : entries) {
                if (e.equals(archiveEntry) || e.endsWith("/" + archiveEntry)) {
                    return e;
                }
            }
        }

        // 2. Збіг за fileName
        if (fileName != null && !fileName.isBlank()) {
            for (String e : entries) {
                if (e.endsWith(fileName) || e.endsWith("/" + fileName)) {
                    return e;
                }
            }
        }

        // 3. За назвою книги (нечіткий пошук)
        if (title != null && !title.isBlank()) {
            String normalizedTitle = title.toLowerCase().replaceAll("[\\s_\\-]+", "");
            for (String e : entries) {
                String name = Path.of(e).getFileName().toString().toLowerCase().replaceAll("[\\s_\\-]+", "");
                if (name.contains(normalizedTitle)) {
                    return e;
                }
            }
        }

        // 4. Перший FB2
        for (String e : entries) {
            if (e.toLowerCase().endsWith(".fb2")) {
                return e;
            }
        }
        return null;
    }
}