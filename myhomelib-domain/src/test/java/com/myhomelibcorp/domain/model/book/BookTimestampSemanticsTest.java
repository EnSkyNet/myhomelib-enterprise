package com.myhomelibcorp.domain.model.book;

import com.myhomelibcorp.domain.model.valueobject.BookMetadata;

import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class BookTimestampSemanticsTest {
    @Test
    void preservesUnknownUpdateDateInsteadOfInventingCreatedAt() {
        LocalDateTime created = LocalDateTime.of(2020, 1, 2, 3, 4);
        Book book = Book.builder()
                .id(BookId.generate())
                .title("Book")
                .metadata(BookMetadata.empty())
                .file(new BookFile("book.fb2", "", "", 1L, ""))
                .createdAt(created)
                .updateDate(null)
                .build();

        assertThat(book.getCreatedAt()).isEqualTo(created);
        assertThat(book.getUpdateDate()).isNull();
    }
    @Test
    void storageAndAvailabilityChangesDoNotInventCatalogUpdateDate() {
        LocalDateTime sourceUpdated = LocalDateTime.of(2020, 5, 6, 7, 8);
        Book book = Book.builder()
                .id(BookId.generate())
                .title("Book")
                .metadata(BookMetadata.empty())
                .file(new BookFile("old.fb2", "", "", 1L, ""))
                .updateDate(sourceUpdated)
                .local(false)
                .build();

        Book withFile = book.changeFile(new BookFile("new.fb2", "", "", 2L, ""));
        Book local = withFile.withLocalAvailability(true, null);

        assertThat(withFile.getUpdateDate()).isEqualTo(sourceUpdated);
        assertThat(local.getUpdateDate()).isEqualTo(sourceUpdated);
    }

    @Test
    void manualMetadataEditsPreserveSourceUpdateDate() {
        LocalDateTime sourceUpdated = LocalDateTime.of(2021, 7, 8, 9, 10);
        Book book = Book.builder()
                .id(BookId.generate())
                .title("Original")
                .metadata(BookMetadata.empty())
                .file(new BookFile("book.fb2", "", "", 1L, ""))
                .updateDate(sourceUpdated)
                .build();

        Book renamed = book.changeTitle("Renamed");
        Book metadataChanged = renamed.changeMetadata(BookMetadata.empty());

        assertThat(renamed.getUpdateDate()).isEqualTo(sourceUpdated);
        assertThat(metadataChanged.getUpdateDate()).isEqualTo(sourceUpdated);
    }

    @Test
    void manualMetadataEditsDoNotInventUnknownSourceUpdateDate() {
        Book book = Book.builder()
                .id(BookId.generate())
                .title("Original")
                .metadata(BookMetadata.empty())
                .file(new BookFile("book.fb2", "", "", 1L, ""))
                .updateDate(null)
                .build();

        assertThat(book.changeTitle("Renamed").getUpdateDate()).isNull();
        assertThat(book.changeMetadata(BookMetadata.empty()).getUpdateDate()).isNull();
    }

}
