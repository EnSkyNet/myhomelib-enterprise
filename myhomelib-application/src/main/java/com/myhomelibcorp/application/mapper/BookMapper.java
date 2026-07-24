package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public abstract class BookMapper {

    @Autowired
    protected GenreRepository genreRepository;

    @Mapping(target = "id", expression = "java(book.getId().asString())")
    @Mapping(target = "authorsText", expression = "java(book.authorsText())")
    @Mapping(target = "genres", ignore = true)
    @Mapping(target = "genresText", expression = "java(mapGenresToText(book.getGenres()))")
    @Mapping(target = "language", expression = "java(book.getLanguage() != null ? book.getLanguage().toString() : \"\")")
    @Mapping(target = "isbn", expression = "java(book.getIsbn() != null ? book.getIsbn().toString() : null)")
    @Mapping(target = "year", constant = "0")
    @Mapping(target = "publisher", constant = "")
    @Mapping(target = "fileSize", source = "file.fileSize")
    @Mapping(target = "fileName", source = "file.fileName")
    @Mapping(target = "folder", source = "file.folder")
    @Mapping(target = "archiveEntry", source = "file.archiveEntry")
    @Mapping(target = "collectionRoot", source = "file.collectionRoot")
    public abstract BookDto toDto(Book book);

    protected String mapGenresToText(List<Genre> genres) {
        if (genres == null || genres.isEmpty()) {
            return "";
        }
        return genres.stream()
                .map(genre -> genreRepository.getGenreName(genre.getId().asString()))
                .collect(Collectors.joining(", "));
    }
}