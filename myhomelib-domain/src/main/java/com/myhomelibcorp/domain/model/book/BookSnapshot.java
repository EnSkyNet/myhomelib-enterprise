package com.myhomelibcorp.domain.model.book;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.Builder;
import lombok.Value;

/**
 * Знімок книги для передачі в подіях та індексації.
 * Містить тільки необхідні поля, уникає передачі великих об'єктів.
 */
@Value
@Builder
public class BookSnapshot {
    BookId id;
    String title;
    String authorsText;
    String series;
    String genresText;
    String keywords;
    String annotation;

    public static BookSnapshot fromBook(Book book) {
        return BookSnapshot.builder()
                .id(book.getId())
                .title(book.getTitle())
                .authorsText(book.authorsText())
                .series(book.getSeries())
                .genresText(book.genresText())
                .keywords(book.getKeywords())
                .annotation(book.getAnnotation())
                .build();
    }
}