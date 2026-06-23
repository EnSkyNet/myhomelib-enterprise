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
import java.util.LinkedList;
import java.util.Queue;
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

    private static final Charset[] ZIP_CHARSETS = {
            Charset.forName("CP866"),
            Charset.forName("Windows-1251"),
            Charset.forName("UTF-8"),
            Charset.forName("IBM-866")
    };

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
        String zipFolder = file.getParent() != null ? file.getParent().toString() : "";

        Exception lastException = null;
        for (Charset charset : ZIP_CHARSETS) {
            try {
                InputStream fis = Files.newInputStream(file);
                ZipInputStream zis = new ZipInputStream(fis, charset);
                log.debug("Спроба розпакувати з кодуванням: {}", charset);

                ZipIterator iterator = new ZipIterator(zis, depth + 1, zipFileName, zipFolder, charset);
                Spliterator<Book> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
                return StreamSupport.stream(spliterator, false);
            } catch (Exception e) {
                log.debug("Не вдалося прочитати ZIP з кодуванням {}: {}", charset, e.getMessage());
                lastException = e;
            }
        }

        log.error("Не вдалося прочитати ZIP-архів жодним з підтримуваних кодувань: {}", file);
        throw new BusinessException(ErrorCode.IMPORT_FAILED,
                "Не вдалося прочитати ZIP-архів: " + file.getFileName(), lastException);
    }

    @Override
    public String getFormatName() {
        return "ZIP";
    }

    /**
     * Внутрішній ітератор для ZIP – використовує чергу для накопичення книг.
     */
    private class ZipIterator implements java.util.Iterator<Book> {
        private final ZipInputStream zis;
        private final int nextDepth;
        private final String zipFileName;
        private final String zipFolder;
        private final Charset charset;
        private final Queue<Book> bookQueue = new LinkedList<>();
        private boolean finished;
        private int entryCount = 0;

        public ZipIterator(ZipInputStream zis, int nextDepth, String zipFileName, String zipFolder, Charset charset) {
            this.zis = zis;
            this.nextDepth = nextDepth;
            this.zipFileName = zipFileName;
            this.zipFolder = zipFolder;
            this.charset = charset;
            processNextEntry();
        }

        @Override
        public boolean hasNext() {
            return !bookQueue.isEmpty() || (!finished && processNextEntry());
        }

        @Override
        public Book next() {
            return bookQueue.poll();
        }

        /**
         * Обробляє наступний запис у ZIP-архіві, додаючи книги до черги.
         * Повертає true, якщо черга поповнилася.
         */
        private boolean processNextEntry() {
            if (finished) return false;

            try {
                ZipEntry entry = zis.getNextEntry();
                if (entry == null) {
                    finished = true;
                    zis.close();
                    log.info("ZIP-архів оброблено, всього записів: {}", entryCount);
                    return false;
                }
                entryCount++;
                String entryName = entry.getName();
                String decodedName = decodeEntryName(entryName);
                String fileName = Path.of(decodedName).getFileName().toString();

                if (entry.isDirectory()) {
                    log.trace("Пропускаємо директорію: {}", decodedName);
                    return processNextEntry();
                }

                log.debug("Обробка запису #{}: {}", entryCount, decodedName);

                Path tempPath = Path.of(decodedName);
                BookImporterPort importer;
                try {
                    importer = importerRegistry.findImporter(tempPath);
                } catch (IllegalArgumentException e) {
                    log.debug("Немає імпортера для запису: {}", decodedName);
                    zis.closeEntry();
                    return processNextEntry();
                }

                if (importer == null) {
                    zis.closeEntry();
                    return processNextEntry();
                }

                // Якщо це вкладений ZIP – рекурсивно
                if (importer instanceof ZipImporter) {
                    Path tempFile = Files.createTempFile("zip_nested_", "_" + fileName);
                    try {
                        Files.copy(zis, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        zis.closeEntry();
                        try (Stream<Book> nestedStream = ((ZipImporter) importer).importBooksInternal(tempFile, nextDepth)) {
                            nestedStream.forEach(bookQueue::add);
                        }
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }
                } else {
                    // Звичайний файл
                    Path tempFile = Files.createTempFile("zip_import_", "_" + fileName);
                    try {
                        Files.copy(zis, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        zis.closeEntry();
                        String originalName = fileName;
                        String folder = zipFolder + java.io.File.separator + zipFileName;

                        try (Stream<Book> bookStream = importer.importBooks(tempFile)) {
                            bookStream.forEach(book -> {
                                String currentFileName = book.getFileName();
                                if (currentFileName == null || currentFileName.startsWith("zip_import_") || currentFileName.isEmpty()) {
                                    currentFileName = originalName;
                                }
                                Book enrichedBook = Book.builder()
                                        .id(book.getId())
                                        .title(book.getTitle())
                                        .authors(book.getAuthors())
                                        .genres(book.getGenres())
                                        .series(book.getSeries())
                                        .sequenceNumber(book.getSequenceNumber())
                                        .language(book.getLanguage())
                                        .fileName(currentFileName)
                                        .folder(folder)
                                        .archiveEntry(decodedName)
                                        .fileSize(book.getFileSize())
                                        .keywords(book.getKeywords())
                                        .annotation(book.getAnnotation())
                                        .updateDate(book.getUpdateDate())
                                        .build();
                                bookQueue.add(enrichedBook);
                            });
                        }
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }
                }

                return true;

            } catch (Exception e) {
                log.error("Помилка обробки запису", e);
                finished = true;
                try {
                    zis.close();
                } catch (Exception ex) {
                    // ignore
                }
                return false;
            }
        }

        private String decodeEntryName(String name) {
            try {
                if (isValidUtf8(name)) {
                    return name;
                }
                byte[] bytes = name.getBytes(charset);
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return name;
            }
        }

        private boolean isValidUtf8(String s) {
            for (char c : s.toCharArray()) {
                if (c > 0x7F) {
                    try {
                        String test = new String(s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                java.nio.charset.StandardCharsets.UTF_8);
                        if (!test.equals(s)) {
                            return false;
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}