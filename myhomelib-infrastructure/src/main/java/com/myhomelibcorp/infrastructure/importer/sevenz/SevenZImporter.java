package com.myhomelibcorp.infrastructure.importer.sevenz;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.importer.archive.ArchiveEntryImportSupport;
import com.myhomelibcorp.infrastructure.importer.archive.ArchiveImportSupport;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Lazy
@Component
@Slf4j
public class SevenZImporter implements BookImporterPort {

    @Lazy @Autowired private ImporterRegistry importerRegistry;

    @Override
    public boolean supports(Path file) {
        return SupportedFormatRegistry.standard().isFormat(file, "7z");
    }

    @Override public String getFormatName() { return "7Z"; }

    @Override
    public Stream<Book> importBooks(Path file) {
        try {
            Path safeFile = SafeFilePublication.requireSafeRegularFile(file, "7Z import source");
            SevenZHandle handle = openSevenZNoFollow(safeFile);
            SevenZIterator iterator = new SevenZIterator(safeFile, handle);
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED), false)
                    .onClose(iterator::close);
        } catch (IOException e) {
            throw new RuntimeException("Не вдалося відкрити 7z: " + file, e);
        }
    }

    @Override
    public long countBooks(Path file) {
        long count = 0;
        try {
            Path safeFile = SafeFilePublication.requireSafeRegularFile(file, "7Z import source");
            try (SevenZHandle handle = openSevenZNoFollow(safeFile)) {
            SevenZFile archive = handle.archive();
            int entries = 0;
            // getEntries() exposes archive metadata without advancing the decoder.
            // getNextEntry() may skip unread solid-stream data and trigger unbounded work.
            for (SevenZArchiveEntry entry : archive.getEntries()) {
                if (++entries > ArchiveSafetyLimits.MAX_ENTRY_COUNT) return -1;
                if (!entry.isDirectory() && ArchiveImportSupport.isSupportedBookEntry(entry.getName(), importerRegistry)) count++;
            }
            return count;
            }
        } catch (Exception e) {
            return -1;
        }
    }


    private final class SevenZIterator implements Iterator<Book>, AutoCloseable {
        private final Path archivePath;
        private final SevenZHandle handle;
        private final SevenZFile archive;
        private final Queue<Book> queue = new ArrayDeque<>();
        private boolean finished;
        private int entryCount;
        private long totalDecompressedBytes;

        private SevenZIterator(Path archivePath, SevenZHandle handle) {
            this.archivePath = archivePath;
            this.handle = handle;
            this.archive = handle.archive();
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
                    if (entry.isDirectory() || !ArchiveImportSupport.isSafeEntryName(name) || ArchiveImportSupport.isNestedArchive(name)) {
                        totalDecompressedBytes = discardCurrentEntry();
                        continue;
                    }
                    if (entry.getSize() > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                        log.warn("Пропущено великий 7z entry: {}", name);
                        totalDecompressedBytes = discardCurrentEntry();
                        continue;
                    }

                    BookImporterPort importer;
                    try { importer = importerRegistry.findImporter(ArchiveImportSupport.importerProbePath(name)); }
                    catch (Exception unsupported) {
                        totalDecompressedBytes = discardCurrentEntry();
                        continue;
                    }

                    totalDecompressedBytes = ArchiveEntryImportSupport.materializeAndImport(
                            archivePath,
                            name,
                            importer,
                            buffer -> archive.read(buffer, 0, buffer.length),
                            totalDecompressedBytes,
                            queue);
                } catch (IOException unreadable) {
                    // Never represent a corrupt tail as a successfully completed archive.
                    close();
                    throw new UncheckedIOException("Не вдалося дочитати 7z " + archivePath, unreadable);
                } catch (RuntimeException failure) {
                    close();
                    throw failure;
                }
            }
        }

        private long discardCurrentEntry() throws IOException {
            return ArchiveEntryImportSupport.discardEntry(
                    buffer -> archive.read(buffer, 0, buffer.length), totalDecompressedBytes);
        }

        @Override public void close() {
            if (finished) return;
            finished = true;
            try { handle.close(); } catch (Exception ignored) { }
        }
    }

    private SevenZHandle openSevenZNoFollow(Path source) throws IOException {
        Path safe = SafeFilePublication.requireSafeRegularFile(source, "7Z import source");
        FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            SevenZFile archive = SevenZFile.builder()
                    .setSeekableByteChannel(channel)
                    .setMaxMemoryLimitKiB(ArchiveSafetyLimits.SEVEN_Z_MEMORY_LIMIT_KIB)
                    .get();
            return new SevenZHandle(channel, archive);
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    private record SevenZHandle(FileChannel channel, SevenZFile archive) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                archive.close();
            } catch (IOException closeArchive) {
                failure = closeArchive;
            }
            try {
                channel.close();
            } catch (IOException closeChannel) {
                if (failure == null) failure = closeChannel;
                else failure.addSuppressed(closeChannel);
            }
            if (failure != null) throw failure;
        }
    }
}
