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
    // Some catalogues use 1-based series indexes which are inconsistent with
    // actual volume labels (or combine volumes 1-2 into a single source file).
    private static final java.util.regex.Pattern VOLUME = java.util.regex.Pattern.compile(
            "(?iu)\\b(?:том|книга|volume|vol\\.?|book)\\s*[#№]?\\s*(\\d{1,4})(?:\\s*[-–—]\\s*(\\d{1,4}))?");
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
        long explicitVolumes = ordered.stream().filter(book -> !volumeNumbers(book.getTitle()).isEmpty()).count();
        boolean preferVolumeLabels = explicitVolumes > 0 && explicitVolumes * 4 >= ordered.size() * 3L;
        ordered.sort(Comparator
                .comparing((Book book) -> preferVolumeLabels
                                ? volumeNumbers(book.getTitle()).stream().findFirst().orElse(book.getSequenceNumber())
                                : book.getSequenceNumber(),
                        Comparator.nullsLast(Integer::compareTo))
                .thenComparing(book -> normalize(book.getTitle()))
                .thenComparing(book -> book.getId().asString()));
        return new SeriesPlan(leadAuthor.getFullName(), series, List.copyOf(ordered));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static List<Integer> volumeNumbers(String title) {
        if (title == null || title.isBlank()) return List.of();
        java.util.regex.Matcher found = VOLUME.matcher(title);
        java.util.LinkedHashSet<Integer> numbers = new java.util.LinkedHashSet<>();
        while (found.find()) {
            int first = Integer.parseInt(found.group(1));
            int last = found.group(2) == null ? first : Integer.parseInt(found.group(2));
            if (first < 1 || last < first || last - first > 30) continue;
            for (int number = first; number <= last; number++) numbers.add(number);
        }
        return List.copyOf(numbers);
    }

    public record SeriesPlan(String author, String series, List<Book> books) {
        public SeriesPlan {
            books = List.copyOf(books);
            if (books.isEmpty()) throw new IllegalArgumentException("Series is empty");
        }
        public List<BookId> bookIds() { return books.stream().map(Book::getId).toList(); }

        /** Distinguish physical book files from volumes represented by multi-volume editions. */
        public int recognizedVolumeCount() {
            java.util.Set<Integer> counted = new java.util.HashSet<>();
            for (Book book : books) {
                List<Integer> labels = volumeNumbers(book.getTitle());
                if (labels.isEmpty()) return books.size();
                counted.addAll(labels);
            }
            return Math.max(books.size(), counted.size());
        }

        public String sizeLabel() {
            int volumes = recognizedVolumeCount();
            return books.size() + " книг" + (volumes > books.size() ? ", " + volumes + " томів" : "");
        }
    }
}
