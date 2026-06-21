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
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
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
    public Stream<Book> importBooks(Path file) {
        return importBooksInternal(file, 0);
    }

    private Stream<Book> importBooksInternal(Path file, int depth) {
        if (depth > MAX_UNPACK_DEPTH) {
            log.warn("Перевищено максимальну глибину розпакування ZIP ({}): {}", MAX_UNPACK_DEPTH, file);
            return Stream.empty();
        }

        log.info("Імпорт ZIP-архіву (глибина {}): {}", depth, file);
        String zipFileName = file.getFileName().toString();

        try {
            InputStream fis = Files.newInputStream(file);
            ZipInputStream zis = new ZipInputStream(fis, ZIP_CHARSET);

            // Створюємо ітератор для лінивого читання
            Iterator<Book> iterator = new ZipIterator(zis, depth + 1, zipFileName);
            Spliterator<Book> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
            return StreamSupport.stream(spliterator, false);
        } catch (Exception e) {
            log.error("Помилка обробки ZIP-архіву: {}", file, e);
            throw new BusinessException(ErrorCode.IMPORT_FAILED,
                    "Помилка ZIP-архіву: " + e.getMessage() + " (архів: " + file.getFileName() + ")", e);
        }
    }

    @Override
    public String getFormatName() {
        return "ZIP";
    }

    /**
     * Внутрішній ітератор для ZIP – ліниве читання записів.
     */
    private class ZipIterator implements Iterator<Book> {
        private final ZipInputStream zis;
        private final int nextDepth;
        private final String zipFileName;
        private ZipEntry nextEntry;
        private boolean finished;

        public ZipIterator(ZipInputStream zis, int nextDepth, String zipFileName) {
            this.zis = zis;
            this.nextDepth = nextDepth;
            this.zipFileName = zipFileName;
            try {
                this.nextEntry = zis.getNextEntry();
                if (this.nextEntry == null) {
                    this.finished = true;
                }
            } catch (Exception e) {
                this.finished = true;
                log.error("Помилка читання ZIP", e);
            }
        }

        @Override
        public boolean hasNext() {
            return !finished;
        }

        @Override
        public Book next() {
            if (finished || nextEntry == null) {
                return null;
            }
            ZipEntry entry = nextEntry;
            try {
                // Перейти до наступного запису
                nextEntry = zis.getNextEntry();
                if (nextEntry == null) {
                    finished = true;
                    zis.close();
                }

                if (entry.isDirectory()) {
                    return null;
                }

                String entryName = entry.getName();
                Path tempPath = Path.of(entryName);
                BookImporterPort importer = null;
                try {
                    importer = importerRegistry.findImporter(tempPath);
                } catch (IllegalArgumentException e) {
                    log.debug("Немає імпортера для запису: {}", entryName);
                    return null;
                }

                if (importer != null) {
                    if (importer instanceof ZipImporter) {
                        // Вкладений ZIP – рекурсивно
                        Path tempFile = Files.createTempFile("zip_nested_", "_" + entryName);
                        try {
                            Files.copy(zis, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            Stream<Book> nestedStream = ((ZipImporter) importer).importBooksInternal(tempFile, nextDepth);
                            // Беремо перший елемент (або можна повернути всі, але це ускладнює)
                            // Для спрощення – повертаємо першу книгу з вкладеного архіву
                            return nestedStream.findFirst().orElse(null);
                        } finally {
                            Files.deleteIfExists(tempFile);
                        }
                    } else {
                        Path tempFile = Files.createTempFile("zip_import_", "_" + entryName);
                        try {
                            Files.copy(zis, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            String originalName = Path.of(entryName).getFileName().toString();
                            try (Stream<Book> bookStream = importer.importBooks(tempFile)) {
                                return bookStream
                                        .map(book -> {
                                            // Перевизначаємо fileName та folder
                                            String currentFileName = book.getFileName();
                                            if (currentFileName == null || currentFileName.startsWith("zip_import_") || currentFileName.isEmpty()) {
                                                return Book.builder()
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
                                                return Book.builder()
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
                                        })
                                        .findFirst()
                                        .orElse(null);
                            }
                        } finally {
                            Files.deleteIfExists(tempFile);
                        }
                    }
                }
                return null;
            } catch (Exception e) {
                log.error("Помилка обробки запису", e);
                finished = true;
                return null;
            }
        }
    }
}