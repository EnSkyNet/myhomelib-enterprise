package com.myhomelibcorp.application.metadata.merge;

import com.myhomelibcorp.application.metadata.MetadataLookupService;
import com.myhomelibcorp.application.metadata.MetadataLookupResult;
import com.myhomelibcorp.application.metadata.MetadataQuery;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/** Loads a book off the UI thread, performs provider lookup and returns non-destructive review previews. */
@Service
public class MetadataReviewService {
    private final BookQueryRepository books;
    private final MetadataLookupService lookup;
    private final MetadataMergePreviewService previews;
    private final ExecutorPort executor;

    public MetadataReviewService(BookQueryRepository books, MetadataLookupService lookup,
                                 MetadataMergePreviewService previews, ExecutorPort executor) {
        this.books = Objects.requireNonNull(books, "books");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.previews = Objects.requireNonNull(previews, "previews");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public CompletableFuture<MetadataReviewLookupResult> lookup(BookId bookId) {
        return lookup(bookId, new AtomicBoolean(), lookup::search);
    }

    public CompletableFuture<MetadataReviewLookupResult> lookup(
            BookId bookId, Duration timeout, AtomicBoolean cancelled) {
        Objects.requireNonNull(cancelled, "cancelled");
        return lookup(bookId, cancelled, query -> lookup.search(query, timeout, cancelled));
    }

    private CompletableFuture<MetadataReviewLookupResult> lookup(
            BookId bookId, AtomicBoolean cancelled,
            Function<MetadataQuery, CompletableFuture<MetadataLookupResult>> search) {
        Objects.requireNonNull(bookId, "bookId");
        CompletableFuture<Book> loaded;
        try {
            loaded = executor.submit(() -> {
                if (cancelled.get()) throw new CancellationException("Metadata lookup cancelled");
                return books.findById(bookId)
                        .orElseThrow(() -> new IllegalStateException("Book not found: " + bookId));
            });
        } catch (RuntimeException rejected) {
            return CompletableFuture.failedFuture(rejected);
        }
        return loaded.thenCompose(book -> search.apply(queryFor(book))
                        .thenApply(result -> new MetadataReviewLookupResult(
                                result.candidates().stream().map(candidate -> previews.preview(book, candidate)).toList(),
                                result.issues(), result.cancelled())));
    }

    private static MetadataQuery queryFor(Book book) {
        if (book.getIsbn() != null) return MetadataQuery.byIsbn(book.getIsbn().value()).withLimit(10);
        String author = book.getAuthors().isEmpty() ? "" : book.getAuthors().get(0).getFullName();
        return new MetadataQuery("", book.getTitle(), author, 10);
    }
}
