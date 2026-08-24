package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.BookListItem;
import com.myhomelibcorp.domain.model.book.Book;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Mapper(componentModel = "spring")
public interface BookListItemMapper {

    DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Mapping(target = "id", expression = "java(book.getId().asString())")
    @Mapping(target = "title", source = "title")
    @Mapping(target = "authorsText", expression = "java(book.authorsText())")
    @Mapping(target = "series", source = "series")
    @Mapping(target = "sequenceNumber", source = "sequenceNumber")
    @Mapping(target = "genresText", expression = "java(book.genresText())")
    @Mapping(target = "rate", source = "rate")
    @Mapping(target = "progress", source = "progress")
    @Mapping(target = "fileSize", source = "file.fileSize")
    @Mapping(target = "language", expression = "java(book.getLanguage() != null ? book.getLanguage().toString() : \"\")")
    @Mapping(target = "fileName", source = "file.fileName")
    @Mapping(target = "folder", source = "file.folder")
    @Mapping(target = "archiveEntry", source = "file.archiveEntry")
    @Mapping(target = "collectionRoot", source = "file.collectionRoot")
    @Mapping(target = "coverHash", ignore = true)
    @Mapping(target = "annotation", source = "annotation")
    @Mapping(target = "local", source = "local")
    @Mapping(target = "updateDate", expression = "java(formatDate(book.getUpdateDate()))")
    @Mapping(target = "createdAt", expression = "java(formatDate(book.getCreatedAt()))")
    BookListItem toListItem(Book book);

    default String formatDate(LocalDateTime date) {
        return date != null ? date.format(DATE_FORMATTER) : "";
    }
}