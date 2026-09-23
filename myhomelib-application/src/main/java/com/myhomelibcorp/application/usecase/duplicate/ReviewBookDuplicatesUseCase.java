package com.myhomelibcorp.application.usecase.duplicate;

import com.myhomelibcorp.application.duplicate.fuzzy.DuplicateReviewSuggestion;
import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateDetector;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.duplicate.FuzzyDuplicateCandidateLookup;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads a bounded candidate set and turns it into explainable, non-destructive duplicate suggestions. */
@Component
public class ReviewBookDuplicatesUseCase {
    public static final int DEFAULT_LIMIT = 80;
    public static final int MAX_LIMIT = 200;

    private final BookQueryRepository books;
    private final FuzzyDuplicateCandidateLookup candidateLookup;
    private final FuzzyDuplicateDetector detector;
    private final BookMapper mapper;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for isolated tests/non-Spring embedding. */
    public ReviewBookDuplicatesUseCase(BookQueryRepository books,
                                       FuzzyDuplicateCandidateLookup candidateLookup,
                                       FuzzyDuplicateDetector detector, BookMapper mapper) {
        this(books, candidateLookup, detector, mapper, null);
    }

    @Autowired
    public ReviewBookDuplicatesUseCase(BookQueryRepository books,
                                       FuzzyDuplicateCandidateLookup candidateLookup,
                                       FuzzyDuplicateDetector detector, BookMapper mapper,
                                       LibraryOperationCoordinator operationCoordinator) {
        this.books = books;
        this.candidateLookup = candidateLookup;
        this.detector = detector;
        this.mapper = mapper;
        this.operationCoordinator = operationCoordinator;
    }

    public List<DuplicateReviewSuggestion> review(String sourceBookId) {
        return review(BookId.fromString(sourceBookId), DEFAULT_LIMIT);
    }

    public List<DuplicateReviewSuggestion> review(BookId sourceBookId, int limit) {
        if (operationCoordinator == null) return reviewLocked(sourceBookId, limit);
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            List<DuplicateReviewSuggestion> result = reviewLocked(sourceBookId, limit);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private List<DuplicateReviewSuggestion> reviewLocked(BookId sourceBookId, int limit) {
        if (sourceBookId == null) throw new IllegalArgumentException("sourceBookId is required");
        int boundedLimit = Math.max(1, Math.min(MAX_LIMIT, limit));
        Book source = books.findById(sourceBookId)
                .orElseThrow(() -> new IllegalArgumentException("Book not found: " + sourceBookId.asString()));

        List<BookId> candidateIds = candidateLookup.findCandidateIds(source, boundedLimit);
        if (candidateIds == null || candidateIds.isEmpty()) return List.of();

        // Preserve the bounded lookup order and de-duplicate defensive adapter results.
        Map<BookId, Integer> order = new LinkedHashMap<>();
        for (BookId id : candidateIds) {
            if (id != null && !id.equals(sourceBookId) && order.size() < boundedLimit) {
                order.putIfAbsent(id, order.size());
            }
        }
        if (order.isEmpty()) return List.of();

        List<Book> candidates = books.findByIds(List.copyOf(order.keySet()));
        return detector.suggest(source, candidates).stream()
                .map(match -> candidates.stream()
                        .filter(book -> book.getId().equals(match.rightBookId()))
                        .findFirst()
                        .map(candidate -> new DuplicateReviewSuggestion(
                                mapper.toDto(source), mapper.toDto(candidate), match.score(), match.reasons()))
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .toList();
    }
}
