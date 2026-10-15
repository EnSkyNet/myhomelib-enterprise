package com.myhomelibcorp.infrastructure.importer.rar;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.importer.archive.ArchiveEntryImportSupport;
import com.myhomelibcorp.infrastructure.importer.archive.ArchiveImportSupport;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Lazy
@Component
@Slf4j
public class RarImporter implements BookImporterPort {

    @Lazy @Autowired private ImporterRegistry importerRegistry;

    @Override
    public boolean supports(Path file) {
        return SupportedFormatRegistry.standard().isFormat(file, "rar");
    }

    @Override public String getFormatName() { return "RAR"; }

    @Override
    public Stream<Book> importBooks(Path file) {
        try {
            Path safeFile = SafeFilePublication.requireSafeRegularFile(file, "RAR import source");
            InputStream raw = Files.newInputStream(safeFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            Archive archive;
            try {
                archive = new Archive(raw);
            } catch (Exception failure) {
                try { raw.close(); } catch (Exception closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
            if (archive.isPasswordProtected()) {
                archive.close();
                throw new IllegalArgumentException("RAR archive is password-protected: " + file);
            }
            RarIterator iterator = new RarIterator(safeFile, archive, archive.getFileHeaders());
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
                    .onClose(iterator::close);
        } catch (Exception e) {
            throw new RuntimeException("Не вдалося відкрити RAR: " + file, e);
        }
    }

    @Override
    public long countBooks(Path file) {
        try {
            Path safeFile = SafeFilePublication.requireSafeRegularFile(file, "RAR import source");
            try (InputStream raw = Files.newInputStream(safeFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                 Archive archive = new Archive(raw)) {
            if (archive.isPasswordProtected()) return -1;
            long count = 0;
            int entries = 0;
            for (FileHeader header : archive.getFileHeaders()) {
                if (++entries > ArchiveSafetyLimits.MAX_ENTRY_COUNT) return -1;
                if (!header.isDirectory() && ArchiveImportSupport.isSupportedBookEntry(header.getFileName(), importerRegistry)) count++;
            }
            return count;
            }
        } catch (Exception e) {
            return -1;
        }
    }


    private final class RarIterator implements Iterator<Book>, AutoCloseable {
        private final Path archivePath;
        private final Archive archive;
        private final Iterator<FileHeader> headers;
        private final boolean requiresSequentialConsumption;
        private final Queue<Book> queue = new ArrayDeque<>();
        private boolean finished;
        private int entryCount;
        private long totalDecompressedBytes;

        private RarIterator(Path archivePath, Archive archive, List<FileHeader> headers) {
            this.archivePath = archivePath;
            this.archive = archive;
            this.headers = headers.iterator();
            // Junrar internally extracts preceding members to a NullOutputStream when
            // a solid member is requested. If any member/header marks the archive solid,
            // process skipped members explicitly so that hidden decompression is bounded.
            this.requiresSequentialConsumption = (archive.getMainHeader() != null && archive.getMainHeader().isSolid())
                    || headers.stream().anyMatch(FileHeader::isSolid);
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
                    close();
                    throw new IllegalStateException("RAR exceeds archive entry safety limit: " + archivePath);
                }
                String name = header.getFileName();
                if (requiresSequentialConsumption && header.getFullUnpackSize() > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                    close();
                    throw new IllegalStateException("RAR entry exceeds per-entry decompression safety limit: " + name);
                }
                try {
                    if (header.isDirectory() || !ArchiveImportSupport.isSafeEntryName(name) || ArchiveImportSupport.isNestedArchive(name)) {
                        discardSolidEntry(header);
                        continue;
                    }
                    BookImporterPort importer;
                    try { importer = importerRegistry.findImporter(ArchiveImportSupport.importerProbePath(name)); }
                    catch (Exception unsupported) {
                        discardSolidEntry(header);
                        continue;
                    }

                    try (InputStream in = archive.getInputStream(header)) {
                        totalDecompressedBytes = ArchiveEntryImportSupport.materializeAndImport(
                                archivePath,
                                name,
                                importer,
                                in::read,
                                totalDecompressedBytes,
                                queue);
                    }
                } catch (ArchiveEntryImportSupport.CumulativeArchiveLimitException fatal) {
                    close();
                    throw new IllegalStateException("RAR exceeds cumulative decompression safety limit: " + archivePath, fatal);
                } catch (IOException unreadable) {
                    // An I/O failure can corrupt the remainder of an archive. The caller
                    // must roll back previously imported books, not report a partial success.
                    close();
                    throw new UncheckedIOException("Не вдалося прочитати RAR " + archivePath, unreadable);
                } catch (CancellationException interrupted) {
                    close();
                    throw interrupted;
                } catch (RuntimeException entryFailure) {
                    log.warn("Пропущено пошкоджений RAR entry {}: {}", name, entryFailure.getMessage());
                }
            }
        }

        private void discardSolidEntry(FileHeader header) throws IOException {
            if (!requiresSequentialConsumption) return;
            try (InputStream in = archive.getInputStream(header)) {
                totalDecompressedBytes = ArchiveEntryImportSupport.discardEntry(in::read, totalDecompressedBytes);
            } catch (CancellationException interrupted) {
                throw interrupted;
            } catch (RuntimeException extractionFailure) {
                // A failed skip in a solid archive can leave Junrar needing to skip the
                // same member internally on the next extraction. Abort instead of
                // treating it as a recoverable malformed book.
                throw new IOException("Failed to safely consume skipped solid RAR entry: "
                        + header.getFileName(), extractionFailure);
            }
        }

        @Override public void close() {
            if (finished) return;
            finished = true;
            try { archive.close(); } catch (Exception ignored) { }
        }
    }
}
