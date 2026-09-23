package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
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
            ResolvedBookContent resolved = resolveBookContentUseCase.execute(
                    book, ResolveBookContentUseCase.DETAILS_EXTENSIONS);
            String name = book.getArchiveEntry();
            if (name == null || name.isBlank()) {
                name = book.getFileName();
                if (resolved.temporary()) {
                    String ext = extension(resolved.path().getFileName().toString());
                    String stem = stripExtension(name);
                    name = stem + (ext.isBlank() ? "" : "." + ext);
                }
            }
            return Optional.of(new Download(name == null || name.isBlank() ? "book" : name, resolved));
        } catch (IOException | RuntimeException | Error failure) {
            if (lease != null) lease.markFailed(failure);
            throw failure;
        } finally {
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

    public record Download(String fileName, ResolvedBookContent content) implements AutoCloseable {
        @Override public void close() { content.close(); }
    }
}
