package com.myhomelibcorp.application.content.maintenance;

import com.myhomelibcorp.application.content.ContentExtractionContext;
import com.myhomelibcorp.application.content.ContentExtractionRequest;
import com.myhomelibcorp.application.content.ContentExtractionService;
import com.myhomelibcorp.application.content.ContentExtractionSource;
import com.myhomelibcorp.application.content.ContentExtractionStatus;
import com.myhomelibcorp.application.content.index.ContentIndexEntry;
import com.myhomelibcorp.application.content.index.ContentIndexHealth;
import com.myhomelibcorp.application.dto.BookArtifactDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import com.myhomelibcorp.shared.util.ThrowableMessages;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Diagnostics and fail-safe rebuild orchestration for the independent full-text index. */
@Service
public class ContentIndexMaintenanceService {
    private static final SupportedFormatRegistry FORMATS = SupportedFormatRegistry.standard();
    private static final Set<String> FULL_TEXT_EXTENSIONS = FORMATS.extensions(f -> f.fullTextSupported());

    private final ContentIndexPort contentIndex;
    private final BookQueryRepository books;
    private final BookMapper bookMapper;
    private final ResolveBookContentUseCase resolveBookContent;
    private final ContentExtractionService extractionService;

    public ContentIndexMaintenanceService(ContentIndexPort contentIndex,
                                          BookQueryRepository books,
                                          BookMapper bookMapper,
                                          ResolveBookContentUseCase resolveBookContent,
                                          ContentExtractionService extractionService) {
        this.contentIndex = Objects.requireNonNull(contentIndex);
        this.books = Objects.requireNonNull(books);
        this.bookMapper = Objects.requireNonNull(bookMapper);
        this.resolveBookContent = Objects.requireNonNull(resolveBookContent);
        this.extractionService = Objects.requireNonNull(extractionService);
    }

    public ContentIndexHealth health(String collectionId) {
        return contentIndex.health(requiredCollection(collectionId));
    }

    public ContentIndexRebuildResult rebuild(String collectionId,
                                             BooleanSupplier cancelled,
                                             Consumer<ContentIndexRebuildProgress> progress) {
        String id = requiredCollection(collectionId);
        BooleanSupplier cancel = cancelled == null ? () -> false : cancelled;
        Consumer<ContentIndexRebuildProgress> listener = progress == null ? ignored -> { } : progress;
        long totalBooks = Math.max(0L, books.countAll());
        AtomicLong processed = new AtomicLong();
        AtomicLong indexedArtifacts = new AtomicLong();
        listener.accept(new ContentIndexRebuildProgress(0L, totalBooks, "", "starting"));

        try (Stream<Book> source = books.streamAll()) {
            Stream<ContentIndexEntry> entries = source
                    .filter(Objects::nonNull)
                    .filter(book -> !book.isDeleted())
                    .flatMap(book -> {
                        checkCancelled(cancel);
                        listener.accept(new ContentIndexRebuildProgress(processed.get(), totalBooks, book.getTitle(), "extracting"));
                        List<ContentIndexEntry> result = entriesForBook(book, cancel);
                        indexedArtifacts.addAndGet(result.size());
                        long done = processed.incrementAndGet();
                        listener.accept(new ContentIndexRebuildProgress(done, totalBooks, book.getTitle(), "extracted"));
                        return result.stream();
                    });
            // LuceneContentIndexService builds into a sibling directory and only swaps after
            // every entry is written successfully. Any exception leaves the active index intact.
            contentIndex.rebuild(id, entries::iterator);
            ContentIndexHealth health = contentIndex.health(id);
            listener.accept(new ContentIndexRebuildProgress(processed.get(), totalBooks, "", "complete"));
            return new ContentIndexRebuildResult(ContentIndexRebuildResult.Status.COMPLETED,
                    processed.get(), indexedArtifacts.get(), health, "");
        } catch (CancellationException cancelledFailure) {
            ContentIndexHealth health = contentIndex.health(id);
            return new ContentIndexRebuildResult(ContentIndexRebuildResult.Status.CANCELLED,
                    processed.get(), indexedArtifacts.get(), health, "cancelled");
        } catch (RuntimeException failure) {
            ContentIndexHealth health = contentIndex.health(id);
            return new ContentIndexRebuildResult(ContentIndexRebuildResult.Status.FAILED,
                    processed.get(), indexedArtifacts.get(), health, ThrowableMessages.rootMessage(failure));
        }
    }

    private List<ContentIndexEntry> entriesForBook(Book book, BooleanSupplier cancelled) {
        BookDto dto = bookMapper.toDto(book);
        List<BookArtifactDto> candidates = dto.getArtifacts().isEmpty()
                ? List.of(legacyArtifact(dto))
                : dto.getArtifacts().stream().filter(Objects::nonNull).filter(BookArtifactDto::isLocal).toList();
        List<ContentIndexEntry> result = new ArrayList<>();
        for (BookArtifactDto artifact : candidates) {
            checkCancelled(cancelled);
            String format = formatOf(artifact);
            if (!supportsFullText(format)) continue;
            BookDto projected = dto.projectArtifact(artifact);
            try (ResolvedBookContent resolved = resolveBookContent.execute(projected, FULL_TEXT_EXTENSIONS)) {
                ContentExtractionContext context = ContentExtractionContext.create(cancelled, ignored -> { });
                var extracted = extractionService.extract(
                        new ContentExtractionRequest(new PathSource(resolved.path()), format), context);
                if (extracted.status() == ContentExtractionStatus.CANCELLED) throw new CancellationException("cancelled");
                if (extracted.status() != ContentExtractionStatus.SUCCESS) {
                    throw new IllegalStateException("Cannot extract " + projected.getTitle() + " [" + format + "]: " + extracted.message());
                }
                result.add(new ContentIndexEntry(dto.getId(), artifact.getId(), extracted.content()));
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot resolve " + projected.getTitle() + ": " + failure.getMessage(), failure);
            }
        }
        return result;
    }

    private static BookArtifactDto legacyArtifact(BookDto dto) {
        String source = dto.getArchiveEntry() == null || dto.getArchiveEntry().isBlank() ? dto.getFileName() : dto.getArchiveEntry();
        String format = FORMATS.detect(source).map(f -> f.id()).orElse("");
        return BookArtifactDto.builder()
                .id(dto.getId() + ":legacy")
                .format(format)
                .fileName(dto.getFileName())
                .folder(dto.getFolder())
                .collectionRoot(dto.getCollectionRoot())
                .archiveEntry(dto.getArchiveEntry())
                .fileSize(dto.getFileSize())
                .local(dto.isLocal())
                .state(dto.isLocal() ? "AVAILABLE" : "MISSING")
                .build();
    }

    private static String formatOf(BookArtifactDto artifact) {
        if (artifact.getFormat() != null && !artifact.getFormat().isBlank()) return artifact.getFormat().trim().toLowerCase(java.util.Locale.ROOT);
        String source = artifact.getArchiveEntry() == null || artifact.getArchiveEntry().isBlank()
                ? artifact.getFileName() : artifact.getArchiveEntry();
        return FORMATS.detect(source).map(f -> f.id()).orElse("");
    }

    private static boolean supportsFullText(String format) {
        return FORMATS.byId(format).map(f -> f.fullTextSupported()).orElse(false);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new CancellationException("cancelled");
    }

    private static String requiredCollection(String collectionId) {
        if (collectionId == null || collectionId.isBlank()) throw new IllegalArgumentException("collectionId is required");
        return collectionId.trim();
    }

    private record PathSource(Path path) implements ContentExtractionSource {
        @Override public String id() { return path.toString(); }
        @Override public String name() { return path.getFileName() == null ? path.toString() : path.getFileName().toString(); }
        @Override public InputStream openStream() throws IOException { return Files.newInputStream(path); }
        @Override public OptionalLong size() {
            try { return OptionalLong.of(Files.size(path)); }
            catch (IOException ignored) { return OptionalLong.empty(); }
        }
    }
}
