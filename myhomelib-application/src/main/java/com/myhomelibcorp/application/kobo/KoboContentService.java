package com.myhomelibcorp.application.kobo;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.application.usecase.conversion.ConvertBookUseCase;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.shared.util.AppPaths;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves the exact content advertised by {@link KoboBookProjection}.
 * Conversion stays in the Application boundary and occurs lazily on first content request.
 */
@Service
public final class KoboContentService {
    private final BookQueryRepository books;
    private final BookResourcePort resources;
    private final ConvertBookUseCase conversions;

    public KoboContentService(BookQueryRepository books, BookResourcePort resources, ConvertBookUseCase conversions) {
        this.books = Objects.requireNonNull(books, "books");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.conversions = Objects.requireNonNull(conversions, "conversions");
    }

    public Optional<Content> open(KoboBookProjection projection) throws Exception {
        if (projection == null) return Optional.empty();
        BookId bookId = BookId.fromString(projection.bookId());
        var book = books.findById(bookId).orElse(null);
        if (book == null || book.isDeleted()) return Optional.empty();

        BookArtifact artifact;
        if (projection.artifactId().startsWith("virtual:")) {
            String[] parts = projection.artifactId().split(":", 3);
            if (parts.length != 3) return Optional.empty();
            String sourceArtifactId;
            try {
                sourceArtifactId = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException invalidId) {
                return Optional.empty();
            }
            String target = parts[2];
            artifact = book.getArtifacts().stream()
                    .filter(BookArtifact::isAvailable)
                    .filter(a -> target.equalsIgnoreCase(a.getFormat()))
                    .filter(a -> sourceArtifactId.equals(a.getMetadata().get("conversion.sourceArtifactId")))
                    .findFirst().orElse(null);
            if (artifact == null) {
                Path root = SafeFilePublication.createDirectoriesNoLinks(
                        AppPaths.cacheDir().resolve("kobo-content"), "Kobo content cache");
                artifact = conversions.execute(ConvertBookUseCase.Request.withDefaults(bookId, target, root)).artifact();
            }
        } else {
            artifact = book.getArtifacts().stream()
                    .filter(BookArtifact::isAvailable)
                    .filter(a -> projection.artifactId().equals(a.getId()))
                    .findFirst().orElse(null);
        }
        if (artifact == null) return Optional.empty();
        var file = artifact.getFile();
        Optional<InputStream> input = resources.readBookData(file.getFileName(), file.getFolder(),
                file.getCollectionRoot(), file.getArchiveEntry());
        if (input.isEmpty()) return Optional.empty();
        String fileName = file.hasArchiveEntry() ? file.getArchiveEntry() : file.getFileName();
        long size = Math.max(0L, file.getFileSize());
        return Optional.of(new Content(fileName == null || fileName.isBlank() ? projection.downloadName() : fileName,
                size, input.get()));
    }

    public record Content(String fileName, long size, InputStream input) implements AutoCloseable {
        public Content {
            fileName = fileName == null || fileName.isBlank() ? "book.epub" : fileName;
            input = Objects.requireNonNull(input, "input");
        }
        public void transferTo(java.io.OutputStream out) throws IOException { input.transferTo(out); }
        @Override public void close() throws IOException { input.close(); }
    }
}
