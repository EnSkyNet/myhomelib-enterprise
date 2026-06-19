package com.myhomelibcorp.infrastructure.importer.zip;

import com.myhomelibcorp.application.port.out.BookImporterPort;
import com.myhomelibcorp.application.port.out.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.shared.exception.BusinessException;
import com.myhomelibcorp.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@Slf4j
public class ZipImporter implements BookImporterPort {

    @Lazy
    @Autowired
    private ImporterRegistry importerRegistry;

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".zip") || name.endsWith(".fb2zip");
    }

    @Override
    public List<Book> importBooks(Path file) {
        log.info("Імпорт ZIP-архіву: {}", file);
        List<Book> allBooks = new ArrayList<>();

        try (InputStream fis = Files.newInputStream(file);
             ZipInputStream zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String entryName = entry.getName();
                Path tempPath = Path.of(entryName);
                BookImporterPort importer = null;
                try {
                    importer = importerRegistry.findImporter(tempPath);
                } catch (IllegalArgumentException e) {
                    log.debug("Немає імпортера для запису: {}", entryName);
                }

                if (importer != null) {
                    Path tempFile = Files.createTempFile("zip_import_", "_" + entryName);
                    try {
                        Files.copy(zis, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        List<Book> books = importer.importBooks(tempFile);
                        allBooks.addAll(books);
                        log.debug("Імпортовано {} книг із запису {}", books.size(), entryName);
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }
                } else {
                    log.debug("Пропускаємо запис {} – непідтримуваний формат", entryName);
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            log.error("Помилка обробки ZIP-архіву: {}", file, e);
            throw new BusinessException(ErrorCode.IMPORT_FAILED, "Помилка ZIP-архіву: " + e.getMessage(), e);
        }

        log.info("З ZIP-архіву {} імпортовано {} книг", file.getFileName(), allBooks.size());
        return allBooks;
    }

    @Override
    public String getFormatName() {
        return "ZIP";
    }
}