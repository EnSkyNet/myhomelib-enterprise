package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.activity.BookActivitySummary;
import com.myhomelibcorp.application.port.out.activity.BookActivityQueryPort;
import com.myhomelibcorp.application.port.out.customfield.CustomFieldRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.customfield.CustomFieldValue;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Builds the search snapshot enrichment that does not belong to Lucene orchestration. */
final class LuceneBookSnapshotEnricher {
    private final CustomFieldRepository customFieldRepository;
    private final BookActivityQueryPort bookActivityQueryPort;

    LuceneBookSnapshotEnricher(CustomFieldRepository customFieldRepository,
                               BookActivityQueryPort bookActivityQueryPort) {
        this.customFieldRepository = customFieldRepository;
        this.bookActivityQueryPort = bookActivityQueryPort;
    }

    Map<String, BookActivitySummary> activitySummaries(List<Book> books) {
        if (bookActivityQueryPort == null || books == null || books.isEmpty()) return Map.of();
        return bookActivityQueryPort.summarize(books.stream()
                .filter(Objects::nonNull)
                .map(Book::getId).filter(Objects::nonNull)
                .map(BookId::asString).toList());
    }

    BookSnapshot enrich(Book book) {
        Map<String, BookActivitySummary> activities = Map.of();
        if (bookActivityQueryPort != null && book != null && book.getId() != null) {
            activities = bookActivityQueryPort.summarize(List.of(book.getId().asString()));
        }
        return enrich(book, activities);
    }

    BookSnapshot enrich(Book book, Map<String, BookActivitySummary> activities) {
        BookSnapshot snapshot = BookSnapshot.fromBook(book);
        Map<Long, CustomFieldValue> values = customFieldRepository == null || book == null || book.getId() == null
                ? Map.of() : customFieldRepository.findValues(book.getId());
        String bookId = book == null || book.getId() == null ? "" : book.getId().asString();
        BookActivitySummary activity = activities == null
                ? BookActivitySummary.empty(bookId)
                : activities.getOrDefault(bookId, BookActivitySummary.empty(bookId));
        return BookSnapshot.builder()
                .id(snapshot.getId()).title(snapshot.getTitle()).authorsText(snapshot.getAuthorsText()).authorIds(snapshot.getAuthorIds())
                .series(snapshot.getSeries()).genresText(snapshot.getGenresText()).genreIds(snapshot.getGenreIds())
                .keywords(snapshot.getKeywords()).annotation(snapshot.getAnnotation()).fileName(snapshot.getFileName())
                .language(snapshot.getLanguage()).rate(snapshot.getRate()).progress(snapshot.getProgress()).year(snapshot.getYear())
                .publisher(snapshot.getPublisher()).libId(snapshot.getLibId()).libraryRate(snapshot.getLibraryRate())
                .translators(snapshot.getTranslators()).city(snapshot.getCity()).sourceUrl(snapshot.getSourceUrl()).isbn(snapshot.getIsbn())
                .createdAt(snapshot.getCreatedAt()).updateDate(snapshot.getUpdateDate()).deleted(snapshot.isDeleted()).local(snapshot.isLocal())
                .noteCount(activity.noteCount()).highlightCount(activity.highlightCount())
                .customFieldValues(values.values().stream().toList()).build();
    }
}
