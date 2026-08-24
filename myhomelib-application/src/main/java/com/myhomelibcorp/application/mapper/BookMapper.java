package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.*;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Mapper(componentModel = "spring")
public interface BookMapper {

    @Mapping(target = "id", expression = "java(book.getId().asString())")
    @Mapping(target = "authorsText", expression = "java(book.authorsText())")
    @Mapping(target = "genres", ignore = true)
    @Mapping(target = "genresText", expression = "java(book.genresText())")
    @Mapping(target = "language", expression = "java(book.getLanguage() != null ? book.getLanguage().toString() : \"\")")
    @Mapping(target = "isbn", expression = "java(book.getIsbn() != null ? book.getIsbn().toString() : null)")
    @Mapping(target = "year", expression = "java(book.getYear())")
    @Mapping(target = "publisher", expression = "java(book.getPublisher())")
    @Mapping(target = "fileSize", source = "file.fileSize")
    @Mapping(target = "fileName", source = "file.fileName")
    @Mapping(target = "folder", source = "file.folder")
    @Mapping(target = "archiveEntry", source = "file.archiveEntry")
    @Mapping(target = "collectionRoot", source = "file.collectionRoot")
    BookDto toDto(Book book);

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
                .language(dto.getLanguage() != null && !dto.getLanguage().isEmpty()
                        ? LanguageCode.of(dto.getLanguage())
                        : LanguageCode.of("uk"))
                .isbn(dto.getIsbn() != null && !dto.getIsbn().isEmpty()
                        ? Isbn.of(dto.getIsbn())
                        : null)
                .review(dto.getReview() != null ? dto.getReview() : "")
                .year(dto.getYear())
                .publisher(dto.getPublisher() != null ? dto.getPublisher() : "")
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