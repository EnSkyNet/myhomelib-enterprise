package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class BookMapper {

    private final GenreService genreService;

    public BookDto toDto(Book book) {
        if (book == null) {
            return null;
        }

        String genresText = book.getGenres().stream()
                .map(genre -> genreService.getGenreName(genre.getId().asString()))
                .collect(Collectors.joining(", "));

        return BookDto.builder()
                .id(book.getId().asString())
                .title(book.getTitle())
                .authorsText(book.authorsText())
                .series(book.getSeries())
                .genresText(genresText)
                .sequenceNumber(book.getSequenceNumber())
                .rate(book.getRate())
                .progress(book.getProgress())
                .language(book.getLanguage() != null ? book.getLanguage().toString() : "")
                .fileSize(book.getFileSize())
                .fileName(book.getFileName())
                .folder(book.getFolder())
                .archiveEntry(book.getArchiveEntry())
                .updateDate(book.getUpdateDate())
                .annotation(book.getAnnotation())
                .deleted(book.isDeleted())
                .local(book.isLocal())
                .review(book.getReview())
                .createdAt(book.getCreatedAt())
                .collectionRoot(book.getCollectionRoot())
                .build();
    }
}