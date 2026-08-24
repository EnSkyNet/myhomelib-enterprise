package com.myhomelibcorp.infrastructure.importer.zip;

import com.myhomelibcorp.infrastructure.importer.archive.ArchiveImportSupport;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.infrastructure.util.LimitedInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.Queue;
import java.util.NoSuchElementException;
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
            Charset.forName("IBM-866"),
            Charset.forName("KOI8-R")
    };

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".zip") || name.endsWith(".fb2zip") || name.endsWith(".fb2.zip") || name.endsWith(".cbz") || name.endsWith(".jar");
    }

    @Override
    public String getFormatName() {
        return "ZIP";
    }

    @Override
    public Stream<Book> importBooks(Path file) {
        return importBooksInternal(file, 0, null);
    }

    /**
     * Внутрішній метод імпорту з підтримкою рекурсії та collectionRoot.
     */
    private Stream<Book> importBooksInternal(Path file, int depth, String collectionRoot) {
        if (depth > MAX_UNPACK_DEPTH) {
            log.warn("Перевищено максимальну глибину розпакування ZIP ({}): {}", MAX_UNPACK_DEPTH, file);
            return Stream.empty();
        }

        if (collectionRoot == null) {
            collectionRoot = file.getParent() != null ? file.getParent().toString() : "";
        }

        log.info("Імпорт ZIP-архіву (глибина {}, root: {}): {}", depth, collectionRoot, file);
        String zipFileName = file.getFileName().toString();
        String zipFolder = file.getParent() != null ? file.getParent().toString() : "";

        Exception lastException = null;
        for (Charset charset : ZIP_CHARSETS) {
            try {
                InputStream fis = Files.newInputStream(file);
                ZipInputStream zis = new ZipInputStream(fis, charset);
                log.debug("Спроба розпакувати з кодуванням: {}", charset);

                ZipIterator iterator = new ZipIterator(zis, depth + 1, zipFileName, zipFolder, charset, collectionRoot);
                Spliterator<Book> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
                return StreamSupport.stream(spliterator, false).onClose(() -> {
                    try {
                        zis.close();
                    } catch (IOException e) {
                        log.warn("Error closing ZipInputStream", e);
                    }
                });
            } catch (Exception e) {
                log.debug("Не вдалося прочитати ZIP з кодуванням {}: {}", charset, e.getMessage());
                lastException = e;
            }
        }

        log.error("Не вдалося прочитати ZIP-архів жодним з підтримуваних кодувань: {}", file, lastException);
        return Stream.empty();
    }

    // ==================== ВНУТРІШНІЙ КЛАС ІТЕРАТОРА ====================

    private class ZipIterator implements java.util.Iterator<Book> {
        private final ZipInputStream zis;
        private final int nextDepth;
        private final String zipFileName;
        private final String zipFolder;
        private final Charset charset;
        private final String collectionRoot;
        private final Queue<Book> bookQueue = new LinkedList<>();
        private boolean finished;
        private int entryCount = 0;
        private long totalDecompressedSize = 0;

        public ZipIterator(ZipInputStream zis, int nextDepth, String zipFileName, String zipFolder,
                           Charset charset, String collectionRoot) {
            this.zis = zis;
            this.nextDepth = nextDepth;
            this.zipFileName = zipFileName;
            this.zipFolder = zipFolder;
            this.charset = charset;
            this.collectionRoot = collectionRoot;
        }

        @Override
        public boolean hasNext() {
            while (bookQueue.isEmpty() && !finished) {
                processNextEntry();
            }
            return !bookQueue.isEmpty();
        }

        @Override
        public Book next() {
            if (!hasNext()) throw new NoSuchElementException();
            return bookQueue.remove();
        }

        private boolean processNextEntry() {
            if (finished) return false;
            if (Thread.currentThread().isInterrupted()) {
                finished = true;
                try { zis.close(); } catch (IOException ignored) { }
                return false;
            }

            try {
                ZipEntry entry = zis.getNextEntry();
                if (entry == null) {
                    finished = true;
                    zis.close();
                    log.info("ZIP-архів оброблено, всього записів: {}", entryCount);
                    return false;
                }

                entryCount++;

                // Перевірка лімітів безпеки
                if (entryCount > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                    log.warn("Перевищено максимальну кількість записів у ZIP: {}", ArchiveSafetyLimits.MAX_ENTRY_COUNT);
                    finished = true;
                    zis.close();
                    return false;
                }

                // Перевірка Zip Slip
                if (!isSafeEntry(entry)) {
                    log.warn("Підозрілий запис (Zip Slip): {}", entry.getName());
                    zis.closeEntry();
                    return true;
                }

                String rawEntryName = entry.getName();
                if (ArchiveImportSupport.isNestedArchive(rawEntryName)) {
                    log.debug("Skipping nested archive (single archive layer is supported): {}", rawEntryName);
                    zis.closeEntry();
                    return true;
                }
                String bestDecoded = decodeEntryName(rawEntryName);
                String displayFileName = Path.of(bestDecoded).getFileName().toString();

                if (containsGibberish(displayFileName)) {
                    displayFileName = Path.of(rawEntryName).getFileName().toString();
                    log.debug("Використовуємо оригінальне ім'я (резерв): {}", displayFileName);
                }

                log.debug("RAW entry name: {}, display file name: {}", rawEntryName, displayFileName);

                if (entry.isDirectory()) {
                    zis.closeEntry();
                    return true;
                }

                // Declared sizes may be -1 for streaming ZIPs. Never trust them alone.
                long entrySize = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (ArchiveSafetyLimits.declaredEntryTooLarge(entrySize)) {
                    log.warn("Пропущено ZIP entry, що перевищує ліміт: {} ({} bytes)", rawEntryName, entrySize);
                    zis.closeEntry();
                    return true;
                }

                Path tempFile = Files.createTempFile("zip_import_", "_" + displayFileName);
                try {
                    // Do NOT close this wrapper: FilterInputStream.close() would close the whole
                    // ZipInputStream and truncate multi-entry imports after the first book.
                    LimitedInputStream limitedIn = new LimitedInputStream(zis, ArchiveSafetyLimits.MAX_ENTRY_BYTES);
                    Files.copy(limitedIn, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    // Перевіряємо фактичний розмір
                    long actualSize = limitedIn.getTotalRead();
                    totalDecompressedSize += actualSize;

                    // Перевірка загального розміру
                    if (totalDecompressedSize > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                        log.warn("Перевищено загальний розмір розпакованих даних: {} > {}",
                                totalDecompressedSize, ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES);
                        finished = true;
                        zis.close();
                        return false;
                    }

                    // Compression-ratio guard uses compressed size, not the declared
                    // uncompressed size (which would make the ratio almost always 1).
                    if (compressedSize > 0 && actualSize > 0) {
                        long ratio = actualSize / Math.max(1, compressedSize);
                        if (ratio > ArchiveSafetyLimits.MAX_COMPRESSION_RATIO) {
                            log.warn("Підозрілий compression ratio: {} (compressed: {}, unpacked: {})",
                                    ratio, compressedSize, actualSize);
                            zis.closeEntry();
                            return true;
                        }
                    }

                    zis.closeEntry();

                    // Визначаємо імпортер за розширенням
                    Path tempPath = Path.of(rawEntryName);
                    BookImporterPort importer;
                    try {
                        importer = importerRegistry.findImporter(tempPath);
                    } catch (IllegalArgumentException e) {
                        try {
                            Path altPath = Path.of(bestDecoded);
                            importer = importerRegistry.findImporter(altPath);
                        } catch (IllegalArgumentException e2) {
                            log.warn("Немає імпортера для запису: {}", bestDecoded);
                            return true;
                        }
                    }

                    if (importer == null) {
                        log.warn("Імпортер не знайдено для запису: {}", rawEntryName);
                        return true;
                    }

                    // Обробка вкладених ZIP або звичайних файлів
                    if (importer instanceof ZipImporter) {
                        try (Stream<Book> nestedStream = ((ZipImporter) importer)
                                .importBooksInternal(tempFile, nextDepth, collectionRoot)) {
                            nestedStream.forEach(bookQueue::add);
                        }
                    } else {
                        final String finalDisplayFileName = displayFileName;
                        final String finalFolder = zipFolder + java.io.File.separator + zipFileName;
                        final String finalArchiveEntry = rawEntryName;
                        final String finalCollectionRoot = collectionRoot;

                        try (Stream<Book> bookStream = importer.importBooks(tempFile)) {
                            bookStream.forEach(book -> {
                                BookFile newFile = new BookFile(
                                        finalDisplayFileName,
                                        finalFolder,
                                        finalArchiveEntry,
                                        book.getFileSize(),
                                        finalCollectionRoot
                                );

                                Book enrichedBook = Book.builder()
                                        .id(book.getId())
                                        .title(book.getTitle())
                                        .authors(book.getAuthors())
                                        .genres(book.getGenres())
                                        .series(book.getSeries())
                                        .sequenceNumber(book.getSequenceNumber())
                                        .metadata(book.getMetadata())
                                        .file(newFile)
                                        .updateDate(book.getUpdateDate())
                                        .build();
                                bookQueue.add(enrichedBook);
                            });
                        }
                    }
                } finally {
                    Files.deleteIfExists(tempFile);
                }

                return true;

            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("Maximum size limit exceeded")) {
                    log.warn("Розмір запису перевищує ліміт: {}", e.getMessage());
                } else {
                    log.error("Помилка обробки запису", e);
                }
                finished = true;
                try {
                    zis.close();
                } catch (Exception ex) {
                    // ignore
                }
                return false;
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

        private boolean isSafeEntry(ZipEntry entry) {
            String name = entry.getName();
            if (name == null || name.isEmpty()) return false;
            if (name.startsWith("/") || name.startsWith("\\") || name.startsWith("..")) return false;
            try {
                Path normalized = Paths.get(name).normalize();
                return !normalized.toString().startsWith("..") && !normalized.isAbsolute();
            } catch (Exception e) {
                return false;
            }
        }

        private String decodeEntryName(String rawName) {
            String best = rawName;
            int bestScore = -1;
            for (Charset cs : ZIP_CHARSETS) {
                try {
                    String decoded = new String(rawName.getBytes(cs), StandardCharsets.UTF_8);
                    int score = countCyrillic(decoded);
                    if (score > bestScore) {
                        bestScore = score;
                        best = decoded;
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
            return best;
        }

        private int countCyrillic(String s) {
            int count = 0;
            for (char c : s.toCharArray()) {
                if (c >= 0x0400 && c <= 0x04FF) {
                    count++;
                }
            }
            return count;
        }

        private boolean containsGibberish(String s) {
            int nonAscii = 0;
            int total = 0;
            for (char c : s.toCharArray()) {
                total++;
                if (c > 0x7F) {
                    nonAscii++;
                }
            }
            return total > 0 && nonAscii > total * 0.3 && countCyrillic(s) < 2;
        }
    }
}