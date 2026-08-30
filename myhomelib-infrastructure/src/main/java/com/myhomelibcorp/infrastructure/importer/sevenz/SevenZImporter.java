package com.myhomelibcorp.infrastructure.importer.sevenz;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.importer.archive.ArchiveImportSupport;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Component
@Slf4j
public class SevenZImporter implements BookImporterPort {

    @Lazy @Autowired private ImporterRegistry importerRegistry;

    @Override
    public boolean supports(Path file) {
        return file != null && file.getFileName().toString().toLowerCase().endsWith(".7z");
    }

    @Override public String getFormatName() { return "7Z"; }

    @Override
    public Stream<Book> importBooks(Path file) {
        try {
            SevenZFile archive = SevenZFile.builder().setFile(file.toFile())
                    .setMaxMemoryLimitKiB(ArchiveSafetyLimits.SEVEN_Z_MEMORY_LIMIT_KIB).get();
            SevenZIterator iterator = new SevenZIterator(file, archive);
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
                    .onClose(iterator::close);
        } catch (IOException e) {
            throw new RuntimeException("Не вдалося відкрити 7z: " + file, e);
        }
    }

    @Override
    public long countBooks(Path file) {
        long count = 0;
        try (SevenZFile archive = SevenZFile.builder().setFile(file.toFile())
                .setMaxMemoryLimitKiB(ArchiveSafetyLimits.SEVEN_Z_MEMORY_LIMIT_KIB).get()) {
            SevenZArchiveEntry entry;
            int entries = 0;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > ArchiveSafetyLimits.MAX_ENTRY_COUNT) return -1;
                if (!entry.isDirectory() && ArchiveImportSupport.isSupportedBookEntry(entry.getName(), importerRegistry)) count++;
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }


    private final class SevenZIterator implements Iterator<Book>, AutoCloseable {
        private final Path archivePath;
        private final SevenZFile archive;
        private final Queue<Book> queue = new ArrayDeque<>();
        private boolean finished;
        private int entryCount;
        private long totalDecompressedBytes;

        private SevenZIterator(Path archivePath, SevenZFile archive) {
            this.archivePath = archivePath;
            this.archive = archive;
        }

        @Override public boolean hasNext() { fill(); return !queue.isEmpty(); }
        @Override public Book next() {
            if (!hasNext()) throw new NoSuchElementException();
            return queue.remove();
        }

        private void fill() {
            while (queue.isEmpty() && !finished) {
                try {
                    if (Thread.currentThread().isInterrupted()) { close(); return; }
                    SevenZArchiveEntry entry = archive.getNextEntry();
                    if (entry == null) { close(); return; }
                    if (++entryCount > ArchiveSafetyLimits.MAX_ENTRY_COUNT) throw new IOException("Забагато записів у 7z");
                    String name = entry.getName();
                    if (entry.isDirectory() || !ArchiveImportSupport.isSafeEntryName(name) || ArchiveImportSupport.isNestedArchive(name)) continue;
                    if (entry.getSize() > ArchiveSafetyLimits.MAX_ENTRY_BYTES) { log.warn("Пропущено великий 7z entry: {}", name); continue; }

                    BookImporterPort importer;
                    try { importer = importerRegistry.findImporter(Path.of(name)); }
                    catch (Exception unsupported) { continue; }

                    Path temp = Files.createTempFile("mhl-7z-entry-", ArchiveImportSupport.suffixFor(name));
                    try {
                        try (var out = Files.newOutputStream(temp)) {
                            byte[] buffer = new byte[64 * 1024];
                            long total = 0;
                            int n;
                            while ((n = archive.read(buffer, 0, buffer.length)) > 0) {
                                total += n;
                                if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) throw new IOException("7z entry перевищив ліміт");
                                out.write(buffer, 0, n);
                            }
                        }
                        totalDecompressedBytes += Files.size(temp);
                        if (totalDecompressedBytes > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                            throw new IOException("7z exceeds cumulative decompression safety limit");
                        }
                        try (Stream<Book> books = importer.importBooks(temp)) {
                            books.filter(java.util.Objects::nonNull)
                                    .map(b -> ArchiveImportSupport.enrich(b, archivePath, name, entry.getSize()))
                                    .forEach(queue::add);
                        }
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                } catch (Exception e) {
                    log.warn("Помилка запису 7z у {}: {}", archivePath, e.getMessage());
                    close();
                }
            }
        }

        @Override public void close() {
            if (finished) return;
            finished = true;
            try { archive.close(); } catch (Exception ignored) { }
        }
    }
}
