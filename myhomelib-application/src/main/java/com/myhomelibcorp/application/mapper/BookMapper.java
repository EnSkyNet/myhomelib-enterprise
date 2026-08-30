package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.*;
import com.myhomelibcorp.domain.service.LanguageResolver;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Mapper(componentModel = "spring")
public interface BookMapper {

    @Mapping(target = "id", expression = "java(book.getId().asString())")
    @Mapping(target = "authorsText", expression = "java(book.authorsText())")
    @Mapping(target = "authors", expression = "java(toAuthorDtos(book))")
    @Mapping(target = "genres", expression = "java(toGenreNames(book))")
    @Mapping(target = "genreItems", expression = "java(toGenreDtos(book))")
    @Mapping(target = "genresText", expression = "java(book.genresText())")
    @Mapping(target = "language", expression = "java(book.getLanguage() != null ? book.getLanguage().toString() : \"\")")
    @Mapping(target = "isbn", expression = "java(book.getIsbn() != null ? book.getIsbn().toString() : null)")
    @Mapping(target = "year", expression = "java(book.getYear())")
    @Mapping(target = "publisher", expression = "java(book.getPublisher())")
    @Mapping(target = "translators", expression = "java(book.getTranslators())")
    @Mapping(target = "city", expression = "java(book.getCity())")
    @Mapping(target = "sourceUrl", expression = "java(book.getSourceUrl())")
    @Mapping(target = "libId", expression = "java(book.getLibId())")
    @Mapping(target = "libraryRate", expression = "java(book.getLibraryRate())")
    @Mapping(target = "fileSize", source = "file.fileSize")
    @Mapping(target = "fileName", source = "file.fileName")
    @Mapping(target = "folder", source = "file.folder")
    @Mapping(target = "archiveEntry", source = "file.archiveEntry")
    @Mapping(target = "collectionRoot", source = "file.collectionRoot")
    BookDto toDto(Book book);


    default List<com.myhomelibcorp.application.dto.AuthorDto> toAuthorDtos(Book book) {
        if (book == null || book.getAuthors() == null) return List.of();
        return book.getAuthors().stream().map(author -> com.myhomelibcorp.application.dto.AuthorDto.builder()
                .id(author.getId() != null ? author.getId().asString() : null)
                .firstName(author.getFirstName())
                .middleName(author.getMiddleName())
                .lastName(author.getLastName())
                .fullName(author.getFullName())
                .shortName(author.getShortName())
                .annotation(author.getAnnotation())
                .build()).toList();
    }

    default List<String> toGenreNames(Book book) {
        if (book == null || book.getGenres() == null) return List.of();
        return book.getGenres().stream().map(Genre::getName).toList();
    }

    default List<com.myhomelibcorp.application.dto.GenreDto> toGenreDtos(Book book) {
        if (book == null || book.getGenres() == null) return List.of();
        return book.getGenres().stream().map(genre -> com.myhomelibcorp.application.dto.GenreDto.builder()
                .code(genre.getId() != null ? genre.getId().asString() : null)
                .name(genre.getName())
                .parentId(genre.getParentId() != null ? genre.getParentId().asString() : null)
                .fb2Code(genre.getFb2Code())
                .build()).toList();
    }

    /**
     * Конвертує BookDto в Book (для використання в портах).
     * Це default метод, який не потребує генерації MapStruct.
     */
    default Book toDomain(BookDto dto) {
        if (dto == null) {
            return null;
        }

        BookId bookId;
        try {
            bookId = BookId.fromString(dto.getId());
        } catch (Exception e) {
            bookId = BookId.generate();
        }

        // Створюємо BookFile
        BookFile file = new BookFile(
                dto.getFileName() != null ? dto.getFileName() : "",
                dto.getFolder() != null ? dto.getFolder() : "",
                dto.getArchiveEntry() != null ? dto.getArchiveEntry() : "",
                dto.getFileSize(),
                dto.getCollectionRoot() != null ? dto.getCollectionRoot() : ""
        );

        // Створюємо BookMetadata
        BookMetadata metadata = BookMetadata.builder()
                .annotation(dto.getAnnotation() != null ? dto.getAnnotation() : "")
                .keywords(dto.getKeywords() != null ? dto.getKeywords() : "")
                .language(LanguageResolver.resolve(dto.getLanguage()))
                .isbn(Isbn.tryParse(dto.getIsbn()).orElse(null))
                .review(dto.getReview() != null ? dto.getReview() : "")
                .year(dto.getYear())
                .publisher(dto.getPublisher() != null ? dto.getPublisher() : "")
                .translators(dto.getTranslators() != null ? dto.getTranslators() : "")
                .city(dto.getCity() != null ? dto.getCity() : "")
                .sourceUrl(dto.getSourceUrl() != null ? dto.getSourceUrl() : "")
                .libId(dto.getLibId() != null ? dto.getLibId() : "")
                .libraryRate(dto.getLibraryRate())
                .rate(dto.getRate())
                .progress(dto.getProgress())
                .build();

        return Book.builder()
                .id(bookId)
                .title(dto.getTitle() != null ? dto.getTitle() : "")
                .authors(new ArrayList<>()) // Автори будуть заповнені окремо
                .genres(new ArrayList<>())  // Жанри будуть заповнені окремо
                .series(dto.getSeries() != null ? dto.getSeries() : "")
                .sequenceNumber(dto.getSequenceNumber() != null ? dto.getSequenceNumber() : 0)
                .metadata(metadata)
                .file(file)
                .updateDate(dto.getUpdateDate() != null ? dto.getUpdateDate() : LocalDateTime.now())
                .createdAt(dto.getCreatedAt() != null ? dto.getCreatedAt() : LocalDateTime.now())
                .deleted(dto.isDeleted())
                .local(dto.isLocal())
                .build();
    }
}