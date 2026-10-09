package com.myhomelibcorp.application.recommendation;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public final class FindSimilarBooksUseCase {
    private final BookQueryRepository books;
    private final BookSimilarityPolicy policy;
    public FindSimilarBooksUseCase(BookQueryRepository books, BookSimilarityPolicy policy) { this.books=Objects.requireNonNull(books); this.policy=Objects.requireNonNull(policy); }

    public List<SimilarBookResult> find(BookId sourceId, int limit) {
        checkCancelled();
        int bounded = Math.max(1, Math.min(50, limit));
        Book source = books.findById(Objects.requireNonNull(sourceId)).orElseThrow(() -> new IllegalArgumentException("Book not found: "+sourceId));
        checkCancelled();
        Comparator<SimilarBookResult> best = Comparator.comparingDouble(SimilarBookResult::score).reversed().thenComparing(SimilarBookResult::bookId);
        Comparator<SimilarBookResult> worst = best.reversed();
        PriorityQueue<SimilarBookResult> top = new PriorityQueue<>(bounded, worst);
        try (var stream = books.streamSimilarityCandidates(source, bounded)) {
            stream.filter(Objects::nonNull).filter(b -> !b.isDeleted()).filter(b -> !b.getId().equals(sourceId)).forEach(candidate -> {
                checkCancelled();
                SimilarBookResult scored=policy.score(source,candidate); if(scored.score()<=0)return;
                if(top.size()<bounded) top.add(scored);
                else if(best.compare(scored,top.peek())<0){ top.poll(); top.add(scored); }
            });
        }
        checkCancelled();
        return top.stream().sorted(best).toList();
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Similar-books lookup cancelled");
        }
    }
}
