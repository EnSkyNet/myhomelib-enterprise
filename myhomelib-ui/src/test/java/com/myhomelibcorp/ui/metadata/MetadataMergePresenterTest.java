package com.myhomelibcorp.ui.metadata;

import com.myhomelibcorp.application.metadata.MetadataCandidate;
import com.myhomelibcorp.application.metadata.MetadataSource;
import com.myhomelibcorp.application.metadata.merge.MetadataMergeField;
import com.myhomelibcorp.application.metadata.merge.MetadataMergePreview;
import com.myhomelibcorp.application.metadata.merge.MetadataMergePreviewService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataMergePresenterTest {
    private final MetadataMergePresenter presenter = new MetadataMergePresenter();
    private final MetadataMergePreviewService previews = new MetadataMergePreviewService();

    @Test
    void exposesCurrentProposedConfidenceSourceAndFieldLabelsForSingleAndBatchReview() {
        MetadataMergePreview first = previews.preview(book("One"), candidate("Remote One", 0.91));
        MetadataMergePreview second = previews.preview(book("Two"), candidate("Remote Two", 0.82));

        var rows = presenter.present(List.of(first, second));

        assertThat(rows).hasSize(14);
        assertThat(rows).extracting(MetadataMergePresenter.Row::bookTitle).contains("One", "Two");
        assertThat(rows).filteredOn(r -> r.bookTitle().equals("One") && r.field() == MetadataMergeField.TITLE)
                .singleElement().satisfies(row -> {
                    assertThat(row.fieldLabel()).isEqualTo("Назва");
                    assertThat(row.currentValue()).isEqualTo("One");
                    assertThat(row.proposedValue()).isEqualTo("Remote One");
                    assertThat(row.confidence()).isEqualTo("91.0%");
                    assertThat(row.source()).isEqualTo("Provider A");
                    assertThat(row.changed()).isTrue();
                });
    }

    private static Book book(String title) {
        return Book.builder().id(BookId.generate()).title(title).file(BookFile.empty()).build();
    }

    private static MetadataCandidate candidate(String title, double confidence) {
        return new MetadataCandidate(new MetadataSource("provider-a", "Provider A", title, ""), confidence,
                title, List.of(), "", null, "", "", "", "");
    }
}
