package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class BookMapper {

    private final GenreRepository genreRepository;

    public BookDto toDto(Book book) {
        if (book == null) return null;

        String genresText = book.getGenres().stream()
                .map(genre -> genreRepository.getGenreName(genre.getId().asString()))
                .collect(Collectors.joining(", "));

        return BookDto.builder()
                .id(book.getId().asString())
                .title(book.getTitle())
                .authorsText(book.authorsText())
                .series(book.getSeries())
                .genresText(genresText)
                .sequenceNumber(book.getSequenceNumber() != null ? book.getSequenceNumber() : 0)
                .language(book.getLanguage() != null ? book.getLanguage().toString() : "")
                .fileSize(book.getFileSize())
                .fileName(book.getFileName())
                .folder(book.getFolder())
                .archiveEntry(book.getArchiveEntry())
                .updateDate(book.getUpdateDate())
                .annotation(book.getAnnotation())
                .deleted(book.isDeleted())
                .local(book.isLocal())
                .review(book.getReview() != null ? book.getReview() : "")
                .createdAt(book.getCreatedAt())
                .collectionRoot(book.getCollectionRoot())
                .rate(book.getRate())
                .progress(book.getProgress())
                .keywords(book.getKeywords())
                .year(0) // тимчасове значення, поки поле не додано в Book
                .publisher("") // тимчасове значення, поки поле не додано в Book
                .isbn(book.getIsbn() != null ? book.getIsbn().toString() : null)
                .build();
    }
}