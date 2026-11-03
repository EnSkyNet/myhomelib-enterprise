package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Finds the entire selected author/series, not only the book rows currently highlighted. */
@Component
public final class SeriesOmnibusPlanner {
    private static final int MAX_BOOKS = 512;
    private final BookQueryRepository repository;

    public SeriesOmnibusPlanner(BookQueryRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public SeriesPlan plan(List<BookId> selected) {
        if (selected == null || selected.isEmpty()) throw new IllegalArgumentException("Select a series book");
        List<Book> seeds = new ArrayList<>();
        for (BookId id : selected) {
            Book book = repository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Selected book is missing: " + id));
            seeds.add(book);
        }
        Book first = seeds.getFirst();
        if (first.getAuthors().isEmpty() || first.getSeries() == null || first.getSeries().isBlank()) {
            throw new IllegalArgumentException("Series export needs an author and series metadata");
        }
        Author leadAuthor = first.getAuthors().getFirst();
        AuthorId author = leadAuthor.getId();
        String series = first.getSeries().trim();
        String normalizedSeries = normalize(series);
        for (Book book : seeds) {
            if (book.isDeleted() || !normalize(book.getSeries()).equals(normalizedSeries)
                    || book.getAuthors().stream().noneMatch(a -> author.equals(a.getId()))) {
                throw new IllegalArgumentException("Selected books must belong to the same author's series");
            }
        }
        LinkedHashMap<BookId, Book> all = new LinkedHashMap<>();
        try (var catalog = repository.streamAll()) {
            catalog.filter(Objects::nonNull)
                    .filter(book -> !book.isDeleted())
                    .filter(book -> normalizedSeries.equals(normalize(book.getSeries())))
                    .filter(book -> book.getAuthors().stream().anyMatch(a -> author.equals(a.getId())))
                    .forEach(book -> {
                        if (all.putIfAbsent(book.getId(), book) == null && all.size() > MAX_BOOKS) {
                            throw new IllegalStateException("Series has more than " + MAX_BOOKS + " books");
                        }
                    });
        }
        // Some repository projections can omit local-only rows: never lose the selected books.
        seeds.forEach(book -> all.putIfAbsent(book.getId(), book));
        List<Book> ordered = new ArrayList<>(all.values());
        ordered.sort(Comparator
                .comparing(Book::getSequenceNumber, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(book -> normalize(book.getTitle()))
                .thenComparing(book -> book.getId().asString()));
        return new SeriesPlan(leadAuthor.getFullName(), series, List.copyOf(ordered));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record SeriesPlan(String author, String series, List<Book> books) {
        public SeriesPlan {
            books = List.copyOf(books);
            if (books.isEmpty()) throw new IllegalArgumentException("Series is empty");
        }
        public List<BookId> bookIds() { return books.stream().map(Book::getId).toList(); }
    }
}
