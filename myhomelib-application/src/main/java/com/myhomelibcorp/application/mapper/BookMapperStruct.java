package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.util.List;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface BookMapperStruct {

    BookMapperStruct INSTANCE = Mappers.getMapper(BookMapperStruct.class);

    @Mapping(target = "id", source = "id.value", qualifiedByName = "uuidToString")
    @Mapping(target = "authorsText", expression = "java(book.authorsText())")
    @Mapping(target = "genres", ignore = true)
    @Mapping(target = "genresText", expression = "java(book.genresText())")
    @Mapping(target = "language", expression = "java(book.getLanguage() != null ? book.getLanguage().toString() : \"\")")
    @Mapping(target = "isbn", expression = "java(book.getIsbn() != null ? book.getIsbn().toString() : null)")
    @Mapping(target = "year", constant = "0")
    @Mapping(target = "publisher", constant = "")
    BookDto toDto(Book book);

    @Named("uuidToString")
    default String uuidToString(java.util.UUID uuid) {
        return uuid != null ? uuid.toString() : null;
    }

    default List<String> mapGenres(List<Genre> genres) {
        if (genres == null) return List.of();
        return genres.stream()
                .map(Genre::getId)
                .map(id -> id != null ? id.asString() : "")
                .collect(Collectors.toList());
    }
}