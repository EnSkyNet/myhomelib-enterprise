package com.myhomelibcorp.application.mapper;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.*;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;

@Component
public class BookMapperHelper {

    /**
     * Конвертує BookDto в Book (для використання в портах).
     */
    public Book toDomain(BookDto dto) {
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
                .authors(new ArrayList<>())
                .genres(new ArrayList<>())
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