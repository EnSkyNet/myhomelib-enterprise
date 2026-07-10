package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class BookListItemMapper {

    private final GenreRepository genreRepository;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public BookListItem toListItem(Book book) {
        if (book == null) return null;

        String genresText = book.getGenres().stream()
                .map(genre -> genreRepository.getGenreName(genre.getId().asString()))
                .collect(Collectors.joining(", "));

        return BookListItem.builder()
                .id(book.getId().asString())
                .title(book.getTitle())
                .authorsText(book.authorsText())
                .series(book.getSeries())
                .genresText(genresText)
                .rate(book.getRate())
                .progress(book.getProgress())
                .fileSize(book.getFileSize())
                .local(book.isLocal())
                .updateDate(book.getUpdateDate() != null ? book.getUpdateDate().format(DATE_FORMATTER) : "")
                .createdAt(book.getCreatedAt() != null ? book.getCreatedAt().format(DATE_FORMATTER) : "")
                .fileName(book.getFileName())
                .folder(book.getFolder())
                .archiveEntry(book.getArchiveEntry())
                .collectionRoot(book.getCollectionRoot())
                .annotation(book.getAnnotation())
                .language(book.getLanguage() != null ? book.getLanguage().toString() : "")
                .build();
    }
}