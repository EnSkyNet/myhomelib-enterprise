package com.myhomelibcorp.application.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookDtoArtifactProjectionTest {

    @Test
    void selectedArtifactProjectionChangesOnlyOperationalStorageFields() {
        BookArtifactDto epub = BookArtifactDto.builder()
                .id("epub").format("epub").fileName("book.epub").folder("epub")
                .collectionRoot("/library").fileSize(2048).local(true).state("AVAILABLE").build();
        BookArtifactDto pdf = BookArtifactDto.builder()
                .id("pdf").format("pdf").fileName("book.pdf").folder("pdf")
                .collectionRoot("/library").fileSize(4096).local(true).state("AVAILABLE").build();
        BookDto book = BookDto.builder()
                .id("11111111-1111-1111-1111-111111111111")
                .title("Logical Book")
                .authorsText("Author")
                .fileName("book.epub").folder("epub").collectionRoot("/library")
                .fileSize(2048).local(true)
                .artifacts(List.of(epub, pdf)).preferredArtifactId("epub")
                .build();

        BookDto projected = book.projectArtifact(pdf);

        assertThat(projected.getId()).isEqualTo(book.getId());
        assertThat(projected.getTitle()).isEqualTo("Logical Book");
        assertThat(projected.getFileName()).isEqualTo("book.pdf");
        assertThat(projected.getFolder()).isEqualTo("pdf");
        assertThat(projected.getFileSize()).isEqualTo(4096);
        assertThat(projected.getPreferredArtifactId()).isEqualTo("epub");
        assertThat(book.getFileName()).isEqualTo("book.epub");
    }
}
