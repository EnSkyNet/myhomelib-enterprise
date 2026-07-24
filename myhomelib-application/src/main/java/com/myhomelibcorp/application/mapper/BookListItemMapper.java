package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public abstract class BookListItemMapper {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Autowired
    protected GenreRepository genreRepository;

    @Mapping(target = "id", expression = "java(book.getId().asString())")
    @Mapping(target = "authorsText", expression = "java(book.authorsText())")
    @Mapping(target = "genresText", expression = "java(mapGenresToText(book.getGenres()))")
    @Mapping(target = "updateDate", expression = "java(formatDate(book.getUpdateDate()))")
    @Mapping(target = "createdAt", expression = "java(formatDate(book.getCreatedAt()))")
    @Mapping(target = "language", expression = "java(book.getLanguage() != null ? book.getLanguage().toString() : \"\")")
    @Mapping(target = "fileSize", source = "file.fileSize")
    @Mapping(target = "fileName", source = "file.fileName")
    @Mapping(target = "folder", source = "file.folder")
    @Mapping(target = "archiveEntry", source = "file.archiveEntry")
    @Mapping(target = "collectionRoot", source = "file.collectionRoot")
    @Mapping(target = "coverHash", ignore = true)
    @Mapping(target = "annotation", source = "annotation")
    @Mapping(target = "rate", source = "rate")
    @Mapping(target = "progress", source = "progress")
    @Mapping(target = "local", source = "local")
    @Mapping(target = "series", source = "series")
    @Mapping(target = "title", source = "title")
    public abstract BookListItem toListItem(Book book);

    protected String mapGenresToText(List<Genre> genres) {
        if (genres == null || genres.isEmpty()) {
            return "";
        }
        return genres.stream()
                .map(genre -> genreRepository.getGenreName(genre.getId().asString()))
                .collect(Collectors.joining(", "));
    }

    protected String formatDate(LocalDateTime date) {
        return date != null ? date.format(DATE_FORMATTER) : "";
    }
}