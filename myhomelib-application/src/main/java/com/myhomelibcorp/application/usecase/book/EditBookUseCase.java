package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Transactional metadata edit boundary for Classic UI.
 * SQLite is authoritative; Lucene synchronization is delegated to CommittedCatalogMutationService
 * and therefore occurs only after the collection transaction commits.
 */
@Service
@RequiredArgsConstructor
public class EditBookUseCase {

    private final BookQueryRepository books;
    private final CommittedCatalogMutationService committedMutations;

    public Book execute(Request request) {
        if (request == null || request.bookId() == null) {
            throw new IllegalArgumentException("Book id is required");
        }
        String title = normalizeRequired(request.title(), "Book title cannot be blank");
        Book current = books.findById(request.bookId())
                .orElseThrow(() -> new BookNotFoundException(request.bookId()));

        BookMetadata old = current.getMetadata();
        BookMetadata metadata = BookMetadata.builder()
                .annotation(nullToEmpty(request.annotation()))
                .keywords(nullToEmpty(request.keywords()))
                .language(request.language() == null ? old.getLanguage() : request.language())
                .isbn(old.getIsbn())
                .review(nullToEmpty(request.review()))
                .year(request.year())
                .publisher(nullToEmpty(request.publisher()))
                .libId(old.getLibId())
                .libraryRate(old.getLibraryRate())
                .translators(old.getTranslators())
                .city(old.getCity())
                .sourceUrl(old.getSourceUrl())
                .rate(old.getRate())
                .progress(old.getProgress())
                .build();

        List<Author> authors = request.authors() == null || request.authors().isEmpty()
                ? current.getAuthors()
                : List.copyOf(request.authors());

        Book updated = Book.builder()
                .id(current.getId())
                .title(title)
                .authors(authors)
                .genres(current.getGenres())
                .series(normalizeNullable(request.series()))
                .sequenceNumber(request.sequenceNumber())
                .metadata(metadata)
                .file(current.getFile())
                .cover(current.getCover())
                .updateDate(LocalDateTime.now())
                .createdAt(current.getCreatedAt())
                .deleted(current.isDeleted())
                .local(current.isLocal())
                .missingSince(current.getMissingSince())
                .build();

        committedMutations.save(updated);
        return updated;
    }

    private static String normalizeRequired(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record Request(
            BookId bookId,
            String title,
            List<Author> authors,
            String series,
            Integer sequenceNumber,
            LanguageCode language,
            Integer year,
            String publisher,
            String keywords,
            String annotation,
            String review) {
        public Request {
            authors = authors == null ? List.of() : List.copyOf(authors);
        }
    }

    public static final class BookNotFoundException extends IllegalStateException {
        public BookNotFoundException(BookId id) {
            super("Book not found: " + id);
        }
    }
}
