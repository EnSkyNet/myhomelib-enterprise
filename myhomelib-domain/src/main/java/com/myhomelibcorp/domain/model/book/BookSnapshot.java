package com.myhomelibcorp.domain.model.book;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/** Lightweight immutable view used by the search index. */
@Value
@Builder
public class BookSnapshot {
    BookId id;
    String title;
    String authorsText;
    String authorIds;
    String series;
    String genresText;
    String genreIds;
    String keywords;
    String annotation;
    String fileName;
    String language;
    Integer rate;
    Integer progress;
    Integer year;
    String publisher;
    String libId;
    Integer libraryRate;
    String translators;
    String city;
    String sourceUrl;
    String isbn;
    LocalDateTime createdAt;
    LocalDateTime updateDate;
    boolean deleted;
    boolean local;

    public static BookSnapshot fromBook(Book book) {
        return BookSnapshot.builder()
                .id(book.getId())
                .title(book.getTitle())
                .authorsText(book.authorsText())
                .authorIds(book.getAuthors().stream().map(a -> a.getId().asString()).collect(java.util.stream.Collectors.joining(" ")))
                .series(book.getSeries())
                .genresText(book.genresText())
                .genreIds(book.getGenres().stream().map(g -> g.getId().asString()).collect(java.util.stream.Collectors.joining(" ")))
                .keywords(book.getKeywords())
                .annotation(book.getAnnotation())
                .fileName(book.getFileName())
                .language(book.getLanguage() != null ? book.getLanguage().value() : "")
                .rate(book.getRate())
                .progress(book.getProgress())
                .year(book.getYear())
                .publisher(book.getPublisher())
                .libId(book.getLibId())
                .libraryRate(book.getLibraryRate())
                .translators(book.getTranslators())
                .city(book.getCity())
                .sourceUrl(book.getSourceUrl())
                .isbn(book.getIsbn() == null ? "" : book.getIsbn().value())
                .createdAt(book.getCreatedAt())
                .updateDate(book.getUpdateDate())
                .deleted(book.isDeleted())
                .local(book.isLocal())
                .build();
    }
}
