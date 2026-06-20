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
import java.nio.charset.Charset;
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

    private static final int MAX_UNPACK_DEPTH = 5;
    private static final Charset ZIP_CHARSET = Charset.forName("windows-1251");

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".zip") || name.endsWith(".fb2zip");
    }

    @Override
    public List<Book> importBooks(Path file) {
        return importBooksInternal(file, 0);
    }

    private List<Book> importBooksInternal(Path file, int depth) {
        if (depth > MAX_UNPACK_DEPTH) {
            log.warn("Перевищено максимальну глибину розпакування ZIP ({}): {}", MAX_UNPACK_DEPTH, file);
            return List.of();
        }

        log.info("Імпорт ZIP-архіву (глибина {}): {}", depth, file);
        List<Book> allBooks = new ArrayList<>();
        String zipFileName = file.getFileName().toString();

        try (InputStream fis = Files.newInputStream(file);
             ZipInputStream zis = new ZipInputStream(fis, ZIP_CHARSET)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;

                String entryName = entry.getName();
                log.debug("Обробка запису: {}", entryName);

                Path tempPath = Path.of(entryName);
                BookImporterPort importer = null;
                try {
                    importer = importerRegistry.findImporter(tempPath);
                } catch (IllegalArgumentException e) {
                    log.debug("Немає імпортера для запису: {}", entryName);
                }

                if (importer != null) {
                    if (importer instanceof ZipImporter) {
                        log.debug("Виявлено вкладений ZIP: {}", entryName);
                        Path tempFile = Files.createTempFile("zip_nested_", "_" + entryName);
                        try {
                            Files.copy(zis, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            List<Book> nestedBooks = ((ZipImporter) importer).importBooksInternal(tempFile, depth + 1);
                            allBooks.addAll(nestedBooks);
                        } finally {
                            Files.deleteIfExists(tempFile);
                        }
                    } else {
                        Path tempFile = Files.createTempFile("zip_import_", "_" + entryName);
                        try {
                            Files.copy(zis, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            List<Book> books = importer.importBooks(tempFile);

                            // Перевизначаємо ім'я файлу та папку
                            String originalName = Path.of(entryName).getFileName().toString();
                            for (Book book : books) {
                                String currentFileName = book.getFileName();
                                // Якщо ім'я тимчасове або порожнє - замінюємо
                                if (currentFileName == null || currentFileName.startsWith("zip_import_") || currentFileName.isEmpty()) {
                                    // Створюємо нову книгу з правильним ім'ям
                                    book = Book.builder()
                                            .id(book.getId())
                                            .title(book.getTitle())
                                            .authors(book.getAuthors())
                                            .genres(book.getGenres())
                                            .series(book.getSeries())
                                            .sequenceNumber(book.getSequenceNumber())
                                            .language(book.getLanguage())
                                            .fileName(originalName)
                                            .folder(zipFileName)
                                            .fileSize(book.getFileSize())
                                            .keywords(book.getKeywords())
                                            .annotation(book.getAnnotation())
                                            .updateDate(book.getUpdateDate())
                                            .build();
                                } else {
                                    // Якщо ім'я вже правильне - просто додаємо папку
                                    book = Book.builder()
                                            .id(book.getId())
                                            .title(book.getTitle())
                                            .authors(book.getAuthors())
                                            .genres(book.getGenres())
                                            .series(book.getSeries())
                                            .sequenceNumber(book.getSequenceNumber())
                                            .language(book.getLanguage())
                                            .fileName(currentFileName)
                                            .folder(zipFileName)
                                            .fileSize(book.getFileSize())
                                            .keywords(book.getKeywords())
                                            .annotation(book.getAnnotation())
                                            .updateDate(book.getUpdateDate())
                                            .build();
                                }
                                allBooks.add(book);
                            }
                            log.debug("Імпортовано {} книг із запису {}", books.size(), entryName);
                        } finally {
                            Files.deleteIfExists(tempFile);
                        }
                    }
                } else {
                    log.debug("Пропускаємо запис {} – непідтримуваний формат", entryName);
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            log.error("Помилка обробки ZIP-архіву: {}", file, e);
            throw new BusinessException(ErrorCode.IMPORT_FAILED,
                    "Помилка ZIP-архіву: " + e.getMessage() + " (архів: " + file.getFileName() + ")", e);
        }

        log.info("З ZIP-архіву {} імпортовано {} книг", file.getFileName(), allBooks.size());
        return allBooks;
    }

    @Override
    public String getFormatName() {
        return "ZIP";
    }
}