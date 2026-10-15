package com.myhomelibcorp.infrastructure.importer.zip;

import com.myhomelibcorp.infrastructure.importer.archive.ArchiveImportSupport;
import com.myhomelibcorp.infrastructure.importer.archive.ArchiveEntryImportSupport;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.SafeZipSupport;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.Queue;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import java.util.Enumeration;

@Lazy
@Component
@Slf4j
public class ZipImporter implements BookImporterPort {

    @Lazy
    @Autowired
    private ImporterRegistry importerRegistry;

    @Override
    public boolean supports(Path file) {
        return SupportedFormatRegistry.standard().isFormat(file, "zip", "jar");
    }

    @Override
    public String getFormatName() {
        return "ZIP";
    }

    @Override
    public Stream<Book> importBooks(Path file) {
        if (file == null) return Stream.empty();

        log.info("Імпорт ZIP-архіву: {}", file);
        try {
            Path safeFile = SafeFilePublication.requireSafeRegularFile(file, "ZIP import source");
            ZipFile zip = SafeZipSupport.openDetected(safeFile, "ZIP import source");
            ZipIterator iterator = new ZipIterator(zip, safeFile);
            Spliterator<Book> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
            return StreamSupport.stream(spliterator, false).onClose(iterator::close);
        } catch (Exception e) {
            throw new RuntimeException("Не вдалося відкрити ZIP-архів: " + file, e);
        }
    }

    @Override
    public long countBooks(Path file) {
        if (file == null) return -1;
        try {
            Path safeFile = SafeFilePublication.requireSafeRegularFile(file, "ZIP import source");
            try (ZipFile zip = SafeZipSupport.openDetected(safeFile, "ZIP import source")) {
                long count = 0;
                int inspected = 0;
                Enumeration<ZipArchiveEntry> entries = zip.getEntries();
                while (entries.hasMoreElements()) {
                    if (++inspected > ArchiveSafetyLimits.MAX_ENTRY_COUNT) return -1;
                    ZipArchiveEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!entry.isDirectory()
                            && ArchiveImportSupport.isSafeEntryName(name)
                            && ArchiveImportSupport.isSupportedBookEntry(name, importerRegistry)) {
                        count++;
                    }
                }
                return count;
            }
        } catch (Exception e) {
            log.debug("Не вдалося порахувати книги у ZIP {}: {}", file, e.getMessage());
            return -1;
        }
    }

    // ==================== ВНУТРІШНІЙ КЛАС ІТЕРАТОРА ====================

    private class ZipIterator implements java.util.Iterator<Book> {
        private final ZipFile zip;
        private final Enumeration<ZipArchiveEntry> entries;
        private final Path archivePath;
        private final Queue<Book> bookQueue = new LinkedList<>();
        private boolean finished;
        private int entryCount;
        private long totalDecompressedSize;

        private ZipIterator(ZipFile zip, Path archivePath) {
            this.zip = zip;
            this.entries = zip.getEntries();
            this.archivePath = archivePath;
        }

        @Override
        public boolean hasNext() {
            while (bookQueue.isEmpty() && !finished) processNextEntry();
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
                if (!entries.hasMoreElements()) {
                    close();
                    log.info("ZIP-архів оброблено, всього записів: {}", entryCount);
                    return false;
                }

                ZipArchiveEntry entry = entries.nextElement();
                if (++entryCount > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                    throw new IOException("ZIP contains more than " + ArchiveSafetyLimits.MAX_ENTRY_COUNT + " entries");
                }

                String rawEntryName = entry.getName();
                if (!ArchiveImportSupport.isSafeEntryName(rawEntryName)) {
                    log.warn("Підозрілий запис (Zip Slip): {}", rawEntryName);
                    return true;
                }
                if (ArchiveImportSupport.isNestedArchive(rawEntryName)) {
                    log.debug("Пропущено вкладений архів (підтримується один archive layer): {}", rawEntryName);
                    return true;
                }
                if (entry.isDirectory()) return true;
                // Check the importer before materializing this ZIP entry. Previously an
                // unsupported member was fully decompressed into a temporary file and
                // discarded, wasting I/O and potentially failing an otherwise valid ZIP.
                BookImporterPort importer;
                try {
                    importer = importerRegistry.findImporter(ArchiveImportSupport.importerProbePath(rawEntryName));
                } catch (IllegalArgumentException unsupported) {
                    log.debug("Пропущено непідтримуваний ZIP entry: {}", rawEntryName);
                    return true;
                }

                long entrySize = entry.getSize();
                long compressedSize = entry.getCompressedSize();
                if (ArchiveSafetyLimits.declaredEntryTooLarge(entrySize)) {
                    log.warn("Пропущено ZIP entry, що перевищує ліміт: {} ({} bytes)", rawEntryName, entrySize);
                    return true;
                }

                Path tempFile = Files.createTempFile("zip_import_", ArchiveImportSupport.suffixFor(rawEntryName));
                try {
                    long actualSize;
                    try (InputStream entryInput = zip.getInputStream(entry)) {
                        long previousTotal = totalDecompressedSize;
                        try {
                            totalDecompressedSize = ArchiveEntryImportSupport.copyEntryBounded(
                                    entryInput::read, tempFile, totalDecompressedSize);
                        } catch (ArchiveEntryImportSupport.EntryLimitException tooLarge) {
                            log.warn("Пропущено ZIP entry, що фактично перевищив ліміт: {}", rawEntryName);
                            return true;
                        }
                        actualSize = totalDecompressedSize - previousTotal;
                    }
                    if (ArchiveSafetyLimits.exceedsCompressionRatio(actualSize, compressedSize)) {
                        log.warn("Підозрілий compression ratio (compressed: {}, unpacked: {})", compressedSize, actualSize);
                        return true;
                    }

                    Queue<Book> parsedEntry = new ArrayDeque<>();
                    try (Stream<Book> bookStream = importer.importBooks(tempFile)) {
                        final long extractedSize = actualSize;
                        bookStream.filter(java.util.Objects::nonNull)
                                .map(book -> ArchiveImportSupport.enrich(book, archivePath, rawEntryName, extractedSize))
                                .forEach(parsedEntry::add);
                    } catch (RuntimeException entryFailure) {
                        if (Thread.currentThread().isInterrupted()) {
                            close();
                            return false;
                        }
                        log.warn("Пропущено пошкоджений ZIP entry {} у {}: {}",
                                rawEntryName, archivePath, entryFailure.getMessage());
                        return true;
                    }
                    // Publish an entry only after the entire parser stream closed successfully.
                    bookQueue.addAll(parsedEntry);
                } finally {
                    Files.deleteIfExists(tempFile);
                }
                return true;
            } catch (IOException e) {
                close();
                throw new UncheckedIOException("Не вдалося прочитати ZIP " + archivePath, e);
            } catch (RuntimeException e) {
                close();
                throw e;
            } catch (Exception e) {
                close();
                throw new RuntimeException("Не вдалося обробити ZIP entry у " + archivePath, e);
            }
        }

        private void close() {
            if (finished) return;
            finished = true;
            try {
                zip.close();
            } catch (IOException e) {
                log.debug("Не вдалося закрити ZIP {}: {}", archivePath, e.getMessage());
            }
        }
    }

}
