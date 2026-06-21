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

    // Список кодувань для спроби (в порядку пріоритету)
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

        // Пробуємо різні кодування
        Exception lastException = null;
        for (Charset charset : ZIP_CHARSETS) {
            try {
                InputStream fis = Files.newInputStream(file);
                ZipInputStream zis = new ZipInputStream(fis, charset);
                log.debug("Спроба розпакувати з кодуванням: {}", charset);

                Iterator<Book> iterator = new ZipIterator(zis, depth + 1, zipFileName, zipFolder, charset);
                Spliterator<Book> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
                return StreamSupport.stream(spliterator, false);
            } catch (Exception e) {
                log.debug("Не вдалося прочитати ZIP з кодуванням {}: {}", charset, e.getMessage());
                lastException = e;
            }
        }

        // Якщо жодне кодування не підійшло
        log.error("Не вдалося прочитати ZIP-архів жодним з підтримуваних кодувань: {}", file);
        throw new BusinessException(ErrorCode.IMPORT_FAILED,
                "Не вдалося прочитати ZIP-архів: " + file.getFileName(), lastException);
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
        private final String zipFolder;
        private final Charset charset;
        private ZipEntry nextEntry;
        private boolean finished;
        private int entryCount = 0;

        public ZipIterator(ZipInputStream zis, int nextDepth, String zipFileName, String zipFolder, Charset charset) {
            this.zis = zis;
            this.nextDepth = nextDepth;
            this.zipFileName = zipFileName;
            this.zipFolder = zipFolder;
            this.charset = charset;
            moveToNextEntry();
        }

        @Override
        public boolean hasNext() {
            if (finished) {
                return false;
            }
            if (nextEntry == null && !finished) {
                moveToNextEntry();
            }
            return !finished && nextEntry != null;
        }

        @Override
        public Book next() {
            if (!hasNext()) {
                return null;
            }

            ZipEntry entry = nextEntry;
            String entryName = entry.getName();
            // Декодуємо ім'я з правильним кодуванням
            String decodedName = decodeEntryName(entryName);
            String fileName = Path.of(decodedName).getFileName().toString();
            entryCount++;

            try {
                if (entry.isDirectory()) {
                    log.trace("Пропускаємо директорію: {}", decodedName);
                    moveToNextEntry();
                    return null;
                }

                log.debug("Обробка запису #{}: {}", entryCount, decodedName);

                // Шукаємо імпортер
                Path tempPath = Path.of(decodedName);
                BookImporterPort importer = null;
                try {
                    importer = importerRegistry.findImporter(tempPath);
                } catch (IllegalArgumentException e) {
                    log.debug("Немає імпортера для запису: {}", decodedName);
                    moveToNextEntry();
                    return null;
                }

                if (importer == null) {
                    moveToNextEntry();
                    return null;
                }

                Book result = null;

                // Якщо це вкладений ZIP – рекурсивно
                if (importer instanceof ZipImporter) {
                    Path tempFile = Files.createTempFile("zip_nested_", "_" + fileName);
                    try {
                        Files.copy(zis, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        closeCurrentEntry();
                        Stream<Book> nestedStream = ((ZipImporter) importer).importBooksInternal(tempFile, nextDepth);
                        result = nestedStream.findFirst().orElse(null);
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }
                } else {
                    // Звичайний файл
                    Path tempFile = Files.createTempFile("zip_import_", "_" + fileName);
                    try {
                        Files.copy(zis, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        closeCurrentEntry();
                        String originalName = fileName;
                        String folder = zipFolder + java.io.File.separator + zipFileName;

                        try (Stream<Book> bookStream = importer.importBooks(tempFile)) {
                            result = bookStream
                                    .map(book -> {
                                        String currentFileName = book.getFileName();
                                        if (currentFileName == null || currentFileName.startsWith("zip_import_") || currentFileName.isEmpty()) {
                                            currentFileName = originalName;
                                        }
                                        return Book.builder()
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
                                    })
                                    .findFirst()
                                    .orElse(null);
                        }
                    } finally {
                        Files.deleteIfExists(tempFile);
                    }
                }

                moveToNextEntry();
                return result;

            } catch (Exception e) {
                log.error("Помилка обробки запису: {}", decodedName, e);
                try {
                    closeCurrentEntry();
                    moveToNextEntry();
                } catch (Exception ex) {
                    finished = true;
                    try {
                        zis.close();
                    } catch (java.io.IOException ignored) {}
                }
                return null;
            }
        }

        /**
         * Декодує ім'я файлу з використанням поточного кодування.
         */
        private String decodeEntryName(String name) {
            try {
                // Якщо ім'я вже виглядає як UTF-8 (без кракозябрів), пробуємо не чіпати
                if (isValidUtf8(name)) {
                    return name;
                }
                byte[] bytes = name.getBytes(charset);
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return name;
            }
        }

        /**
         * Проста перевірка, чи рядок схожий на UTF-8 (без кракозябрів).
         */
        private boolean isValidUtf8(String s) {
            for (char c : s.toCharArray()) {
                if (c > 0x7F) {
                    // Якщо є символи > 127, це може бути UTF-8 або інше кодування
                    // Перевіряємо, чи це валидний UTF-8 символ
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

        private void closeCurrentEntry() throws java.io.IOException {
            zis.closeEntry();
        }

        private void moveToNextEntry() {
            try {
                nextEntry = zis.getNextEntry();
                if (nextEntry == null) {
                    finished = true;
                    zis.close();
                    log.info("ZIP-архів оброблено, всього записів: {}", entryCount);
                }
            } catch (Exception e) {
                log.error("Помилка переходу до наступного запису", e);
                finished = true;
                try {
                    zis.close();
                } catch (java.io.IOException ignored) {}
            }
        }
    }
}