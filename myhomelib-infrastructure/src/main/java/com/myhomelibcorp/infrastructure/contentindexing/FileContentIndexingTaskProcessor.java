package com.myhomelibcorp.infrastructure.contentindexing;

import com.myhomelibcorp.application.content.ContentExtractionContext;
import com.myhomelibcorp.application.content.ContentExtractionRequest;
import com.myhomelibcorp.application.content.ContentExtractionService;
import com.myhomelibcorp.application.content.ContentExtractionSource;
import com.myhomelibcorp.application.content.ContentExtractionStatus;
import com.myhomelibcorp.application.content.index.ContentIndexEntry;
import com.myhomelibcorp.application.content.indexing.ContentIndexingControl;
import com.myhomelibcorp.application.content.indexing.ContentIndexingOutcome;
import com.myhomelibcorp.application.content.indexing.ContentIndexingProgress;
import com.myhomelibcorp.application.content.indexing.ContentIndexingTask;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexingTaskProcessor;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.locks.LockSupport;

@Component
public class FileContentIndexingTaskProcessor implements ContentIndexingTaskProcessor {
    private final ContentExtractionService extractionService;
    private final ContentIndexPort contentIndex;

    public FileContentIndexingTaskProcessor(ContentExtractionService extractionService, ContentIndexPort contentIndex) {
        this.extractionService = extractionService;
        this.contentIndex = contentIndex;
    }

    @Override
    public ContentIndexingOutcome process(ContentIndexingTask task, ContentIndexingControl control) {
        Path path = Path.of(task.sourcePath()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) return ContentIndexingOutcome.failed("Source file is missing: " + path);
        ContentExtractionSource source = new FileSource(path, control);
        ContentExtractionContext context = ContentExtractionContext.create(control::isCancelled,
                p -> control.report(new ContentIndexingProgress(task.taskId(), p.phase(), p.completed(), p.total())));
        var result = extractionService.extract(new ContentExtractionRequest(source, task.format()), context);
        if (control.isCancelled() || result.status() == ContentExtractionStatus.CANCELLED) return ContentIndexingOutcome.cancelled();
        if (result.status() != ContentExtractionStatus.SUCCESS) {
            return ContentIndexingOutcome.failed(result.message());
        }
        if (control.isCancelled()) return ContentIndexingOutcome.cancelled();
        contentIndex.replaceArtifact(task.collectionId(), new ContentIndexEntry(task.bookId(), task.artifactId(), result.content()));
        return ContentIndexingOutcome.completed();
    }

    private static final class FileSource implements ContentExtractionSource {
        private final Path path;
        private final ContentIndexingControl control;
        FileSource(Path path, ContentIndexingControl control) { this.path = path; this.control = control; }
        @Override public String id() { return path.toString(); }
        @Override public String name() { return path.getFileName().toString(); }
        @Override public InputStream openStream() throws IOException {
            InputStream base = Files.newInputStream(path);
            return control.ioBytesPerSecond() <= 0
                    ? base : new RateLimitedInputStream(base, control.ioBytesPerSecond(), control);
        }
        @Override public OptionalLong size() {
            try { return OptionalLong.of(Files.size(path)); }
            catch (IOException ignored) { return OptionalLong.empty(); }
        }
    }

    static final class RateLimitedInputStream extends FilterInputStream {
        private final long bytesPerSecond;
        private final ContentIndexingControl control;
        private final long startedNanos = System.nanoTime();
        private long bytesRead;

        RateLimitedInputStream(InputStream in, long bytesPerSecond, ContentIndexingControl control) {
            super(in);
            this.bytesPerSecond = Math.max(1L, bytesPerSecond);
            this.control = control;
        }

        @Override public int read() throws IOException {
            checkCancelled();
            int value = super.read();
            if (value >= 0) throttle(1);
            return value;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            checkCancelled();
            int read = super.read(b, off, len);
            if (read > 0) throttle(read);
            return read;
        }

        private void throttle(int count) throws IOException {
            bytesRead += count;
            long expectedNanos = (long) ((bytesRead * 1_000_000_000.0) / bytesPerSecond);
            long wait = expectedNanos - (System.nanoTime() - startedNanos);
            while (wait > 0) {
                checkCancelled();
                long slice = Math.min(wait, 50_000_000L);
                LockSupport.parkNanos(slice);
                wait = expectedNanos - (System.nanoTime() - startedNanos);
            }
        }

        private void checkCancelled() throws IOException {
            if (control.isCancelled()) throw new IOException("content indexing cancelled");
        }
    }
}
