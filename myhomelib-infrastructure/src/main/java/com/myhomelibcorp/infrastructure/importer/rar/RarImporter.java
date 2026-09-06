package com.myhomelibcorp.infrastructure.importer.rar;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.importer.archive.ArchiveImportSupport;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.Locale;

@Component
@Slf4j
public class RarImporter implements BookImporterPort {

    @Lazy @Autowired private ImporterRegistry importerRegistry;

    @Override
    public boolean supports(Path file) {
        return SupportedFormatRegistry.standard().isFormat(file, "rar", "cbr");
    }

    @Override public String getFormatName() { return "RAR"; }

    @Override
    public Stream<Book> importBooks(Path file) {
        try {
            Archive archive = new Archive(file.toFile());
            if (archive.isPasswordProtected()) {
                archive.close();
                throw new IllegalArgumentException("RAR archive is password-protected: " + file);
            }
            RarIterator iterator = new RarIterator(file, archive, archive.getFileHeaders());
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
                    .onClose(iterator::close);
        } catch (Exception e) {
            throw new RuntimeException("Не вдалося відкрити RAR: " + file, e);
        }
    }

    @Override
    public long countBooks(Path file) {
        try (Archive archive = new Archive(file.toFile())) {
            if (archive.isPasswordProtected()) return -1;
            long count = 0;
            int entries = 0;
            for (FileHeader header : archive.getFileHeaders()) {
                if (++entries > ArchiveSafetyLimits.MAX_ENTRY_COUNT) return -1;
                if (!header.isDirectory() && ArchiveImportSupport.isSupportedBookEntry(header.getFileName(), importerRegistry)) count++;
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }


    private final class RarIterator implements Iterator<Book>, AutoCloseable {
        private final Path archivePath;
        private final Archive archive;
        private final Iterator<FileHeader> headers;
        private final Queue<Book> queue = new ArrayDeque<>();
        private boolean finished;
        private int entryCount;
        private long totalDecompressedBytes;

        private RarIterator(Path archivePath, Archive archive, List<FileHeader> headers) {
            this.archivePath = archivePath;
            this.archive = archive;
            this.headers = headers.iterator();
        }

        @Override public boolean hasNext() { fill(); return !queue.isEmpty(); }
        @Override public Book next() {
            if (!hasNext()) throw new NoSuchElementException();
            return queue.remove();
        }

        private void fill() {
            while (queue.isEmpty() && !finished) {
                if (Thread.currentThread().isInterrupted()) { close(); return; }
                if (!headers.hasNext()) { close(); return; }
                FileHeader header = headers.next();
                if (++entryCount > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                    log.warn("RAR contains too many entries: {}", archivePath);
                    close();
                    return;
                }
                String name = header.getFileName();
                try {
                    if (header.isDirectory() || !ArchiveImportSupport.isSafeEntryName(name) || ArchiveImportSupport.isNestedArchive(name)) continue;
                    BookImporterPort importer;
                    try { importer = importerRegistry.findImporter(ArchiveImportSupport.importerProbePath(name)); }
                    catch (Exception unsupported) { continue; }

                    Path temp = Files.createTempFile("mhl-rar-entry-", ArchiveImportSupport.suffixFor(name));
                    try (InputStream in = archive.getInputStream(header)) {
                        try (var out = Files.newOutputStream(temp)) {
                            byte[] buffer = new byte[64 * 1024];
                            long total = 0;
                            int n;
                            while ((n = in.read(buffer)) != -1) {
                                total += n;
                                if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) throw new java.io.IOException("RAR entry перевищив ліміт");
                                out.write(buffer, 0, n);
                            }
                        }
                        long size = Files.size(temp);
                        totalDecompressedBytes += size;
                        if (totalDecompressedBytes > ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES) {
                            throw new java.io.IOException("RAR exceeds cumulative decompression safety limit");
                        }
                        try (Stream<Book> books = importer.importBooks(temp)) {
                            books.filter(java.util.Objects::nonNull)
                                    .map(b -> ArchiveImportSupport.enrich(b, archivePath, name, size))
                                    .forEach(queue::add);
                        }
                    } finally {
                        Files.deleteIfExists(temp);
                    }
                } catch (Exception e) {
                    log.warn("Пропущено RAR entry {}: {}", name, e.getMessage());
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
