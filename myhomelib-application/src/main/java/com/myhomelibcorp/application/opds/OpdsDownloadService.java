package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

@Component
public class OpdsDownloadService {
    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final ResolveBookContentUseCase resolveBookContentUseCase;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests and non-Spring embedding. */
    public OpdsDownloadService(LoadBookByIdUseCase loadBookByIdUseCase,
                               ResolveBookContentUseCase resolveBookContentUseCase) {
        this(loadBookByIdUseCase, resolveBookContentUseCase, null);
    }

    @Autowired
    public OpdsDownloadService(LoadBookByIdUseCase loadBookByIdUseCase,
                               ResolveBookContentUseCase resolveBookContentUseCase,
                               LibraryOperationCoordinator operationCoordinator) {
        this.loadBookByIdUseCase = java.util.Objects.requireNonNull(loadBookByIdUseCase, "loadBookByIdUseCase");
        this.resolveBookContentUseCase = java.util.Objects.requireNonNull(resolveBookContentUseCase, "resolveBookContentUseCase");
        this.operationCoordinator = operationCoordinator;
    }

    public Optional<Download> open(String bookId) throws IOException {
        if (bookId == null || bookId.isBlank()) return Optional.empty();
        LibraryOperationCoordinator.Lease lease = operationCoordinator == null
                ? null
                : operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        ResolvedBookContent resolved = null;
        FileChannel source = null;
        try {
            final BookId parsedBookId;
            try {
                parsedBookId = BookId.fromString(bookId);
            } catch (IllegalArgumentException invalidId) {
                return Optional.empty();
            }
            var dto = loadBookByIdUseCase.execute(parsedBookId);
            if (dto.isEmpty()) return Optional.empty();
            final com.myhomelibcorp.application.dto.BookDto book = dto.get();
            resolved = resolveBookContentUseCase.execute(book, ResolveBookContentUseCase.DETAILS_EXTENSIONS);
            String name = book.getArchiveEntry();
            if (name == null || name.isBlank()) {
                name = book.getFileName();
                if (resolved.temporary()) {
                    String ext = extension(resolved.path().getFileName().toString());
                    String stem = stripExtension(name);
                    name = stem + (ext.isBlank() ? "" : "." + ext);
                }
            }

            // Pin the exact source object while QUERY still protects the active collection.
            // The network transfer itself must not retain a lifecycle lease: a slow remote client
            // must never be able to block SWITCH/DELETE indefinitely.
            Path safe = SafeFilePublication.requireSafeRegularFile(resolved.path(), "OPDS download source");
            source = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            long size = source.size();
            Download download = new Download(name == null || name.isBlank() ? "book" : name,
                    resolved, source, size);
            resolved = null; // ownership transferred to Download
            source = null;   // ownership transferred to Download
            return Optional.of(download);
        } catch (IOException | RuntimeException | Error failure) {
            if (lease != null) lease.markFailed(failure);
            throw failure;
        } finally {
            if (source != null) {
                try { source.close(); } catch (IOException ignored) { }
            }
            if (resolved != null) resolved.close();
            if (lease != null) lease.close();
        }
    }

    private static String extension(String value) {
        if (value == null) return "";
        int dot = value.lastIndexOf('.');
        return dot >= 0 ? value.substring(dot + 1) : "";
    }

    private static String stripExtension(String value) {
        if (value == null || value.isBlank()) return "book";
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    public static final class Download implements AutoCloseable {
        private static final int TRANSFER_BUFFER_BYTES = 64 * 1024;

        private final String fileName;
        private final ResolvedBookContent content;
        private final FileChannel source;
        private final long size;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);

        private Download(String fileName, ResolvedBookContent content, FileChannel source, long size) {
            this.fileName = fileName;
            this.content = java.util.Objects.requireNonNull(content, "content");
            this.source = java.util.Objects.requireNonNull(source, "source");
            this.size = Math.max(0L, size);
        }

        public String fileName() { return fileName; }
        public ResolvedBookContent content() { return content; }
        public long size() { return size; }

        /** Streams from the already-open handle captured under the collection QUERY barrier. */
        public void transferTo(OutputStream out) throws IOException {
            java.util.Objects.requireNonNull(out, "out");
            if (closed.get()) throw new IOException("OPDS download is already closed");
            source.position(0L);
            ByteBuffer buffer = ByteBuffer.allocate(TRANSFER_BUFFER_BYTES);
            while (source.read(buffer) >= 0) {
                if (buffer.position() == 0) continue;
                buffer.flip();
                out.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) return;
            try {
                source.close();
            } catch (IOException ignored) {
                // The content cleanup below must still run.
            } finally {
                content.close();
            }
        }
    }

}
