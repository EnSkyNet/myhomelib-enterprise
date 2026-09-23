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
import com.myhomelibcorp.application.dto.BookArtifactDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.port.out.contentindexing.ContentIndexingTaskProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.OptionalLong;
import java.util.concurrent.locks.LockSupport;

@Component
public class FileContentIndexingTaskProcessor implements ContentIndexingTaskProcessor {
    private static final SupportedFormatRegistry FORMATS = SupportedFormatRegistry.standard();
    private static final java.util.Set<String> FULL_TEXT_EXTENSIONS = FORMATS.extensions(f -> f.fullTextSupported());

    private final ContentExtractionService extractionService;
    private final ContentIndexPort contentIndex;
    private final BookQueryRepository books;
    private final BookMapper bookMapper;
    private final ResolveBookContentUseCase resolveBookContent;
    private final CollectionLifecyclePort collectionLifecycle;

    /** Compatibility constructor for isolated processor tests using direct files. */
    public FileContentIndexingTaskProcessor(ContentExtractionService extractionService, ContentIndexPort contentIndex) {
        this(extractionService, contentIndex, null, null, null, null);
    }

    @Autowired
    public FileContentIndexingTaskProcessor(ContentExtractionService extractionService,
                                            ContentIndexPort contentIndex,
                                            BookQueryRepository books,
                                            BookMapper bookMapper,
                                            ResolveBookContentUseCase resolveBookContent,
                                            CollectionLifecyclePort collectionLifecycle) {
        this.extractionService = extractionService;
        this.contentIndex = contentIndex;
        this.books = books;
        this.bookMapper = bookMapper;
        this.resolveBookContent = resolveBookContent;
        this.collectionLifecycle = collectionLifecycle;
    }

    @Override
    public ContentIndexingOutcome process(ContentIndexingTask task, ContentIndexingControl control) {
        ResolvedBookContent resolved = null;
        try {
            resolved = resolveTask(task);
            Path path = resolved == null
                    ? Path.of(task.sourcePath()).toAbsolutePath().normalize()
                    : resolved.path().toAbsolutePath().normalize();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return ContentIndexingOutcome.failed("Source file is missing: " + path);
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
        } catch (IOException | RuntimeException failure) {
            if (control.isCancelled()) return ContentIndexingOutcome.cancelled();
            String message = failure.getMessage();
            return ContentIndexingOutcome.failed(message == null || message.isBlank()
                    ? failure.getClass().getSimpleName() : message);
        } finally {
            if (resolved != null) resolved.close();
        }
    }

    private ResolvedBookContent resolveTask(ContentIndexingTask task) throws IOException {
        if (books == null || bookMapper == null || resolveBookContent == null) return null;
        requireActiveCollection(task.collectionId());
        BookId id;
        try { id = BookId.fromString(task.bookId()); }
        catch (RuntimeException invalid) { throw new IOException("Invalid book id for content indexing", invalid); }
        var book = books.findById(id).orElseThrow(() -> new IOException("Book is no longer present in catalogue"));
        BookDto dto = bookMapper.toDto(book);
        if (!dto.getArtifacts().isEmpty()) {
            BookArtifactDto artifact = dto.getArtifacts().stream()
                    .filter(java.util.Objects::nonNull)
                    .filter(candidate -> task.artifactId().equals(candidate.getId()))
                    .findFirst().orElseThrow(() -> new IOException("Book artifact is no longer present in catalogue"));
            dto = dto.projectArtifact(artifact);
        }
        return resolveBookContent.execute(dto, FULL_TEXT_EXTENSIONS);
    }


    private void requireActiveCollection(String expectedCollectionId) throws IOException {
        if (collectionLifecycle == null) return;
        var active = collectionLifecycle.getCurrentCollection();
        String activeId = active == null || active.getId() == null ? "" : active.getId().trim();
        if (!expectedCollectionId.equals(activeId)) {
            throw new IOException("Content-index task belongs to inactive collection: " + expectedCollectionId);
        }
    }

    private static final class FileSource implements ContentExtractionSource {
        private final Path path;
        private final ContentIndexingControl control;
        FileSource(Path path, ContentIndexingControl control) { this.path = path; this.control = control; }
        @Override public String id() { return path.toString(); }
        @Override public String name() { return path.getFileName().toString(); }
        @Override public InputStream openStream() throws IOException {
            InputStream base = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS);
            return control.ioBytesPerSecond() <= 0
                    ? base : new RateLimitedInputStream(base, control.ioBytesPerSecond(), control);
        }
        @Override public OptionalLong size() {
            try {
                Path safe = SafeFilePublication.requireSafeRegularFile(path, "content-index source");
                var attrs = Files.readAttributes(
                        safe, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                return attrs.isRegularFile() ? OptionalLong.of(attrs.size()) : OptionalLong.empty();
            } catch (IOException ignored) {
                return OptionalLong.empty();
            }
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
