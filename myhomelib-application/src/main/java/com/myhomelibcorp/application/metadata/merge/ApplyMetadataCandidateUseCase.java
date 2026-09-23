package com.myhomelibcorp.application.metadata.merge;

import com.myhomelibcorp.application.metadata.MetadataCandidate;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.Isbn;
import com.myhomelibcorp.domain.service.LanguageResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Applies only fields explicitly selected by the user from a metadata candidate. */
@Service
public class ApplyMetadataCandidateUseCase {
    private final BookQueryRepository books;
    private final CommittedCatalogMutationService committedMutations;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests/non-Spring embedding. */
    public ApplyMetadataCandidateUseCase(BookQueryRepository books, CommittedCatalogMutationService committedMutations) {
        this(books, committedMutations, null);
    }

    @Autowired
    public ApplyMetadataCandidateUseCase(BookQueryRepository books, CommittedCatalogMutationService committedMutations,
                                         LibraryOperationCoordinator operationCoordinator) {
        this.books = Objects.requireNonNull(books, "books");
        this.committedMutations = Objects.requireNonNull(committedMutations, "committedMutations");
        this.operationCoordinator = operationCoordinator;
    }

    public Book execute(Request request) {
        if (operationCoordinator == null || operationCoordinator.isHeldByCurrentThread()) {
            return executeLocked(request);
        }
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.EDIT);
        try {
            Book result = executeLocked(request);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private Book executeLocked(Request request) {
        Book updated = prepare(request);
        if (!request.selectedFields().isEmpty()) committedMutations.save(updated);
        return updated;
    }

    /** Reads the latest state inside the caller's transaction and constructs a selective edit. */
    Book prepare(Request request) {
        Objects.requireNonNull(request, "request");
        Book current = books.findById(request.bookId())
                .orElseThrow(() -> new IllegalStateException("Book not found: " + request.bookId()));
        if (request.selectedFields().isEmpty()) return current;

        MetadataCandidate candidate = request.candidate();
        Set<MetadataMergeField> selected = request.selectedFields();
        String title = selected.contains(MetadataMergeField.TITLE) && !candidate.title().isBlank()
                ? candidate.title() : current.getTitle();
        List<Author> authors = selected.contains(MetadataMergeField.AUTHORS)
                ? toAuthors(candidate.authors()) : current.getAuthors();

        BookMetadata old = current.getMetadata();
        BookMetadata updatedMetadata = BookMetadata.builder()
                .annotation(selected.contains(MetadataMergeField.ANNOTATION) ? candidate.annotation() : old.getAnnotation())
                .keywords(old.getKeywords())
                .language(selected.contains(MetadataMergeField.LANGUAGE)
                        ? LanguageResolver.resolve(candidate.language()) : old.getLanguage())
                .isbn(selected.contains(MetadataMergeField.ISBN)
                        ? Isbn.tryParse(candidate.isbn()).orElse(null) : old.getIsbn())
                .review(old.getReview())
                .year(selected.contains(MetadataMergeField.YEAR) ? candidate.year() : old.getYear())
                .publisher(selected.contains(MetadataMergeField.PUBLISHER) ? candidate.publisher() : old.getPublisher())
                .libId(old.getLibId())
                .libraryRate(old.getLibraryRate())
                .translators(old.getTranslators())
                .city(old.getCity())
                .sourceUrl(old.getSourceUrl())
                .rate(old.getRate())
                .progress(old.getProgress())
                .build();

        Book updated = current.toBuilder()
                .title(title)
                .authors(authors)
                .metadata(updatedMetadata)
                .build();
        return updated;
    }

    private static List<Author> toAuthors(List<String> names) {
        if (names == null || names.isEmpty()) return List.of();
        List<Author> result = new ArrayList<>();
        for (String name : names) {
            if (name == null || name.isBlank()) continue;
            // Provider author strings are display names with provider-specific ordering.
            // Keep that text losslessly instead of guessing first/last-name semantics.
            result.add(new Author(name.trim(), "", ""));
        }
        return List.copyOf(result);
    }

    public record Request(
            BookId bookId,
            MetadataCandidate candidate,
            Set<MetadataMergeField> selectedFields) {
        public Request {
            Objects.requireNonNull(bookId, "bookId");
            Objects.requireNonNull(candidate, "candidate");
            selectedFields = selectedFields == null || selectedFields.isEmpty()
                    ? Set.of() : Set.copyOf(EnumSet.copyOf(selectedFields));
        }
    }
}
