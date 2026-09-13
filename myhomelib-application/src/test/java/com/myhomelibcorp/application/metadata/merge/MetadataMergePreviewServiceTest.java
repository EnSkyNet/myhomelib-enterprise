package com.myhomelibcorp.application.metadata.merge;

import com.myhomelibcorp.application.metadata.MetadataCandidate;
import com.myhomelibcorp.application.metadata.MetadataSource;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataMergePreviewServiceTest {
    private final MetadataMergePreviewService service = new MetadataMergePreviewService();

    @Test
    void buildsCurrentVsProposedRowsWithConfidenceAndAttributionWithoutMutation() {
        Book book = book("Old title");
        MetadataCandidate candidate = candidate("New title");

        MetadataMergePreview preview = service.preview(book, candidate);

        assertThat(preview.bookId()).isEqualTo(book.getId());
        assertThat(preview.confidence()).isEqualTo(0.93);
        assertThat(preview.source().providerId()).isEqualTo("provider-a");
        assertThat(preview.fields()).hasSize(7);
        assertThat(preview.fields()).filteredOn(r -> r.field() == MetadataMergeField.TITLE).singleElement()
                .satisfies(row -> {
                    assertThat(row.currentValue()).isEqualTo("Old title");
                    assertThat(row.proposedValue()).isEqualTo("New title");
                    assertThat(row.changed()).isTrue();
                });
        assertThat(book.getTitle()).isEqualTo("Old title");
    }

    @Test
    void supportsBatchPreviewWithoutApplyingAnything() {
        Book first = book("One");
        Book second = book("Two");
        List<MetadataMergePreview> previews = service.previewBatch(List.of(
                new MetadataMergePreviewService.PreviewRequest(first, candidate("One remote")),
                new MetadataMergePreviewService.PreviewRequest(second, candidate("Two remote"))));

        assertThat(previews).hasSize(2);
        assertThat(previews).extracting(MetadataMergePreview::bookId)
                .containsExactly(first.getId(), second.getId());
        assertThat(first.getTitle()).isEqualTo("One");
        assertThat(second.getTitle()).isEqualTo("Two");
    }

    private static Book book(String title) {
        return Book.builder().id(BookId.generate()).title(title)
                .authors(List.of(new Author("Local Author", "", "")))
                .metadata(BookMetadata.builder().annotation("local annotation").language(LanguageCode.of("uk"))
                        .isbn(Isbn.of("9783161484100")).year(2001).publisher("Local Publisher").build())
                .file(BookFile.empty()).build();
    }

    private static MetadataCandidate candidate(String title) {
        return new MetadataCandidate(
                new MetadataSource("provider-a", "Provider A", "r-1", "https://example.invalid/r-1"),
                0.93, title, List.of("Remote Author"), "9780306406157", 2024,
                "Remote Publisher", "en", "remote annotation", "https://example.invalid/cover.jpg");
    }
}
