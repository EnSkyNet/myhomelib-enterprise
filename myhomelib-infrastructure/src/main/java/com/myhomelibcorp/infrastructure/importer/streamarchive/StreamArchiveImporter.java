package com.myhomelibcorp.infrastructure.importer.streamarchive;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.importer.ImporterRegistry;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.importer.archive.ArchiveImportSupport;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.ArchiveStreamFactory;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** TAR/CPIO and compressed TAR importer. It is sequential and memory bounded. */
@Lazy
@Component
@Slf4j
public class StreamArchiveImporter implements BookImporterPort {
    @Lazy @Autowired private ImporterRegistry registry;

    private final long maxEntryBytes;
    private final long maxTotalDecompressedBytes;

    public StreamArchiveImporter() {
        this(ArchiveSafetyLimits.MAX_ENTRY_BYTES, ArchiveSafetyLimits.MAX_TOTAL_DECOMPRESSED_BYTES);
    }

    /** Package-private limit override keeps boundary tests small without weakening production defaults. */
    StreamArchiveImporter(long maxEntryBytes, long maxTotalDecompressedBytes) {
        if (maxEntryBytes <= 0 || maxTotalDecompressedBytes <= 0) {
            throw new IllegalArgumentException("Archive safety limits must be positive");
        }
        this.maxEntryBytes = maxEntryBytes;
        this.maxTotalDecompressedBytes = maxTotalDecompressedBytes;
    }

    @Override public boolean supports(Path file) {
        return SupportedFormatRegistry.standard().isFormat(file, "tar", "cpio");
    }

    @Override public String getFormatName() { return "TAR/CPIO"; }

    @Override public Stream<Book> importBooks(Path file) {
        try {
            Path safeFile = SafeFilePublication.requireSafeRegularFile(file, "TAR/CPIO import source");
            IteratorImpl it = new IteratorImpl(safeFile, open(safeFile));
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, Spliterator.ORDERED), false)
                    .onClose(it::close);
        } catch (Exception e) {
            throw new RuntimeException("Не вдалося відкрити архів: " + file, e);
        }
    }

    @Override public long countBooks(Path file) {
        long count = 0;
        long totalBytes = 0;
        try {
            Path safeFile = SafeFilePublication.requireSafeRegularFile(file, "TAR/CPIO import source");
            try (ArchiveInputStream<?> in = open(safeFile)) {
                ArchiveEntry entry;
                int entries = 0;
                while ((entry = in.getNextEntry()) != null) {
                    if (++entries > ArchiveSafetyLimits.MAX_ENTRY_COUNT) return -1;

                    // getNextEntry() may have to advance through the preceding member. Drain
                    // every member explicitly first, so skipped/non-book payloads are subject
                    // to the same per-entry and cumulative decompression budgets as books.
                    boolean book = !entry.isDirectory() && isBook(entry.getName());
                    long consumed = consumeBounded(in, null, maxTotalDecompressedBytes - totalBytes);
                    totalBytes += consumed;
                    if (book) count++;
                }
            }
        } catch (Exception e) {
            log.debug("Не вдалося порахувати книги у TAR/CPIO {}: {}", file, e.getMessage());
            return -1;
        }
        return count;
    }

    private boolean isBook(String name) {
        return ArchiveImportSupport.isSafeEntryName(name)
                && ArchiveImportSupport.isSupportedBookEntry(name, registry);
    }

    private ArchiveInputStream<?> open(Path p) throws Exception {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        InputStream raw = new BufferedInputStream(
                Files.newInputStream(p, java.nio.file.LinkOption.NOFOLLOW_LINKS), 64 * 1024);
        InputStream source = raw;
        try {
            if (n.endsWith(".tar.gz") || n.endsWith(".tgz")) {
                source = new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, raw, true);
            } else if (n.endsWith(".tar.bz2") || n.endsWith(".tbz2")) {
                source = new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.BZIP2, raw, true);
            } else if (n.endsWith(".tar.xz") || n.endsWith(".txz")) {
                source = new CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, raw, true);
            }
            String type = n.endsWith(".cpio") ? ArchiveStreamFactory.CPIO : ArchiveStreamFactory.TAR;
            return new ArchiveStreamFactory().createArchiveInputStream(type, source);
        } catch (Exception e) {
            try { source.close(); } catch (Exception ignored) { }
            if (source != raw) try { raw.close(); } catch (Exception ignored) { }
            throw e;
        }
    }

    /**
     * Consumes the current archive member before moving to the next one. A null target
     * means discard without creating a temporary file. Both paths enforce actual bytes
     * read, not only archive metadata, and include ignored members in the archive budget.
     */
    private long consumeBounded(InputStream source, Path target, long remainingArchiveBudget) throws IOException {
        if (remainingArchiveBudget < 0 || remainingArchiveBudget > maxTotalDecompressedBytes) {
            throw new IOException("Invalid remaining archive decompression budget");
        }

        OutputStream sink = target == null ? OutputStream.nullOutputStream() : Files.newOutputStream(target);
        try (OutputStream out = sink) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            while (true) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Archive import interrupted");
                }
                int read = source.read(buffer);
                if (read < 0) break;
                if (read == 0) throw new IOException("Archive entry reader made no progress");
                if (read > buffer.length) throw new IOException("Archive entry reader returned invalid byte count");
                if (total > maxEntryBytes - read) {
                    throw new IOException("Archive entry exceeds per-entry decompression safety limit");
                }
                if (total > remainingArchiveBudget - read) {
                    throw new IOException("Archive exceeds cumulative decompression safety limit");
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new CancellationException("Archive import interrupted");
                }
                out.write(buffer, 0, read);
                total += read;
            }
            return total;
        }
    }

    private final class IteratorImpl implements java.util.Iterator<Book>, AutoCloseable {
        private final Path path;
        private final ArchiveInputStream<?> in;
        private final Queue<Book> queue = new ArrayDeque<>();
        private boolean done;
        private int entries;
        private long totalDecompressedBytes;

        IteratorImpl(Path path, ArchiveInputStream<?> in) {
            this.path = path;
            this.in = in;
        }

        @Override public boolean hasNext() { fill(); return !queue.isEmpty(); }

        @Override public Book next() {
            if (!hasNext()) throw new NoSuchElementException();
            return queue.remove();
        }

        private void fill() {
            while (queue.isEmpty() && !done) {
                try {
                    if (Thread.currentThread().isInterrupted()) {
                        close();
                        return;
                    }
                    ArchiveEntry entry = in.getNextEntry();
                    if (entry == null) {
                        close();
                        return;
                    }
                    if (++entries > ArchiveSafetyLimits.MAX_ENTRY_COUNT) {
                        throw new IOException("Забагато записів у архіві");
                    }

                    String name = entry.getName();
                    boolean book = !entry.isDirectory() && isBook(name);
                    if (!book || entry.getSize() > maxEntryBytes) {
                        if (book) {
                            log.warn("Пропущено TAR/CPIO entry, що перевищує ліміт: {} ({} bytes)", name, entry.getSize());
                        }
                        // Do not let getNextEntry() silently decompress an ignored member.
                        // Drain it now under the same actual-byte limits as imported books.
                        totalDecompressedBytes += consumeBounded(in, null, remainingBudget());
                        continue;
                    }

                    BookImporterPort importer = registry.findImporter(ArchiveImportSupport.importerProbePath(name));
                    Path tmp = Files.createTempFile("mhl-archive-", ArchiveImportSupport.suffixFor(name));
                    try {
                        long extracted = consumeBounded(in, tmp, remainingBudget());
                        totalDecompressedBytes += extracted;
                        Queue<Book> parsedEntry = new ArrayDeque<>();
                        try (Stream<Book> stream = importer.importBooks(tmp)) {
                            stream.filter(java.util.Objects::nonNull)
                                    .map(bookResult -> ArchiveImportSupport.enrich(bookResult, path, name, extracted))
                                    .forEach(parsedEntry::add);
                        } catch (RuntimeException entryFailure) {
                            if (Thread.currentThread().isInterrupted()) {
                                close();
                                return;
                            }
                            log.warn("Archive book entry skipped in {} ({}): {}", path, name, entryFailure.getMessage());
                            parsedEntry.clear();
                        }
                        queue.addAll(parsedEntry);
                    } finally {
                        Files.deleteIfExists(tmp);
                    }
                } catch (IOException ex) {
                    close();
                    throw new UncheckedIOException("Не вдалося прочитати TAR/CPIO " + path, ex);
                } catch (RuntimeException ex) {
                    close();
                    throw ex;
                } catch (Exception ex) {
                    close();
                    throw new IllegalStateException("Не вдалося обробити TAR/CPIO " + path, ex);
                }
            }
        }

        private long remainingBudget() throws IOException {
            if (totalDecompressedBytes < 0 || totalDecompressedBytes > maxTotalDecompressedBytes) {
                throw new IOException("Archive exceeds cumulative decompression safety limit");
            }
            return maxTotalDecompressedBytes - totalDecompressedBytes;
        }

        @Override public void close() {
            if (done) return;
            done = true;
            try { in.close(); } catch (Exception ignored) { }
        }
    }
}
