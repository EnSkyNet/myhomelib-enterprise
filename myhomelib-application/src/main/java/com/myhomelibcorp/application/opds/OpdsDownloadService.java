package com.myhomelibcorp.application.opds;

import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.book.ResolveBookContentUseCase;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OpdsDownloadService {
    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final ResolveBookContentUseCase resolveBookContentUseCase;

    public Optional<Download> open(String bookId) throws IOException {
        final com.myhomelibcorp.application.dto.BookDto book;
        try {
            var dto = loadBookByIdUseCase.execute(BookId.fromString(bookId));
            if (dto.isEmpty()) return Optional.empty();
            book = dto.get();
        } catch (RuntimeException invalidId) {
            return Optional.empty();
        }
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
