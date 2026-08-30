package com.myhomelibcorp.infrastructure.importer.zip;

import com.myhomelibcorp.infrastructure.importer.archive.ArchiveImportSupport;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.util.LimitedInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
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
        if (file == null || !Files.isRegularFile(file)) return Stream.empty();

        log.info("Імпорт ZIP-архіву: {}", file);
        Exception lastException = null;
        for (Charset charset : ZIP_CHARSETS) {
            try {
                InputStream input = Files.newInputStream(file);
                ZipInputStream zip = new ZipInputStream(input, charset);
                ZipIterator iterator = new ZipIterator(zip, file.toAbsolutePath().normalize());
                Spliterator<Book> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
                return StreamSupport.stream(spliterator, false).onClose(iterator::close);
            } catch (Exception e) {
                log.debug("Не вдалося прочитати ZIP з кодуванням {}: {}", charset, e.getMessage());
                lastException = e;
            }
        }

        log.error("Не вдалося прочитати ZIP-архів жодним з підтримуваних кодувань: {}", file, lastException);
        return Stream.empty();
    }

    @Override
    public long countBooks(Path file) {
        if (file == null || !Files.isRegularFile(file)) return -1;
        Exception lastFailure = null;
        for (Charset charset : ZIP_CHARSETS) {
            try (InputStream in = Files.newInputStream(file); ZipInputStream zip = new ZipInputStream(in, charset)) {
                long count = 0;
                int entries = 0;
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (++entries > ArchiveSafetyLimits.MAX_ENTRY_COUNT) return -1;
                    String name = entry.getName();
                    if (!entry.isDirectory()
                            && ArchiveImportSupport.isSafeEntryName(name)
                            && ArchiveImportSupport.isSupportedBookEntry(name, importerRegistry)) {
                        count++;
                    }
                    zip.closeEntry();
                }
                return count;
            } catch (Exception e) {
                lastFailure = e;
            }
        }
        log.debug("Не вдалося порахувати книги у ZIP {}: {}", file,
                lastFailure == null ? "unknown error" : lastFailure.getMessage());
        return -1;
    }

    // ==================== ВНУТРІШНІЙ КЛАС ІТЕРАТОРА ====================

    private class ZipIterator implements java.util.Iterator<Book> {
        private final ZipInputStream zis;
        private final Path archivePath;
        private final Queue<Book> bookQueue = new LinkedList<>();
        private boolean finished;
        private int entryCount = 0;
        private long totalDecompressedSize = 0;

        private ZipIterator(ZipInputStream zis, Path archivePath) {
            this.zis = zis;
            this.archivePath = archivePath;
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
                close();
                return false;
            }

            try {
                ZipEntry entry = zis.getNextEntry();
                if (entry == null) {
                    close();
                    log.info("ZIP-архів оброблено, всього записів: {}", entryCount);
                    return false;
                }

                entryCount++;

                // Перевірка лімітів безпеки
                if (entryCount > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                    log.warn("Перевищено максимальну кількість записів у ZIP: {}", ArchiveSafetyLimits.MAX_ENTRY_COUNT);
                    close();
                    return false;
                }

                // Перевірка Zip Slip
                if (!ArchiveImportSupport.isSafeEntryName(entry.getName())) {
                    log.warn("Підозрілий запис (Zip Slip): {}", entry.getName());
                    zis.closeEntry();
                    return true;
                }

                String rawEntryName = entry.getName();
                if (ArchiveImportSupport.isNestedArchive(rawEntryName)) {
                    log.debug("Пропущено вкладений архів (підтримується один archive layer): {}", rawEntryName);
                    zis.closeEntry();
                    return true;
                }
                String displayFileName = Path.of(rawEntryName.replace('\\', '/')).getFileName().toString();

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
                        close();
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

                    BookImporterPort importer;
                    try {
                        importer = importerRegistry.findImporter(Path.of(rawEntryName.replace('\\', '/')));
                    } catch (IllegalArgumentException e) {
                        log.warn("Немає імпортера для запису: {}", rawEntryName);
                        return true;
                    }

                    try (Stream<Book> bookStream = importer.importBooks(tempFile)) {
                        final long extractedSize = actualSize;
                        bookStream.map(book -> ArchiveImportSupport.enrich(book, archivePath, rawEntryName, extractedSize))
                                .forEach(bookQueue::add);
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
                close();
                return false;
            } catch (Exception e) {
                log.error("Помилка обробки запису", e);
                close();
                return false;
            }
        }

        private void close() {
            finished = true;
            try {
                zis.close();
            } catch (IOException e) {
                log.debug("Не вдалося закрити ZIP stream {}: {}", archivePath, e.getMessage());
            }
        }
    }
}