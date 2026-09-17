package com.myhomelibcorp.ui.duplicate;

import com.myhomelibcorp.application.dto.BookArtifactDto;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.duplicate.ExactDuplicateArtifact;
import com.myhomelibcorp.application.duplicate.ExactDuplicateGroup;
import com.myhomelibcorp.application.duplicate.fuzzy.DuplicateReviewSuggestion;
import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateReason;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.service.LocalizationService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DuplicateReviewPresenterTest {
    private final LocalizationService localization = mock(LocalizationService.class);
    private final DuplicateReviewPresenter presenter = presenter();

    private DuplicateReviewPresenter presenter() {
        for (String value : List.of("Доступний", "Лише віддалено", "Файл відсутній", "Пошкоджений", "Недоступний", "Невідомий стан")) {
            when(localization.tr(value)).thenReturn(value);
        }
        return new DuplicateReviewPresenter(localization);
    }

    @Test
    void userFacingRowContainsScoreReasonsAndArtifactStatesForManualReview() {
        BookDto source = book("1", "Clean Code", "Martin Robert", artifact("epub", "epub", "AVAILABLE", "clean.epub", true));
        BookDto candidate = book("2", "Clean Cod", "Martin Robert", artifact("pdf", "pdf", "REMOTE_ONLY", "clean.pdf", false));
        var suggestion = new DuplicateReviewSuggestion(source, candidate, 0.947,
                List.of(FuzzyDuplicateReason.TITLE_SIMILAR, FuzzyDuplicateReason.AUTHOR_EXACT, FuzzyDuplicateReason.YEAR_MATCH));

        var presentation = presenter.present(List.of(suggestion), source);

        assertThat(presentation.sourceTitle()).isEqualTo("Clean Code");
        assertThat(presentation.sourceArtifacts()).contains("EPUB", "Доступний", "clean.epub");
        assertThat(presentation.rows()).hasSize(1);
        assertThat(presentation.rows().getFirst().candidateId()).isEqualTo("2");
        assertThat(presentation.rows().getFirst().score()).isEqualTo("94.7%");
        assertThat(presentation.rows().getFirst().reasons())
                .contains("схожа назва", "автор збігається", "рік збігається");
        assertThat(presentation.rows().getFirst().artifacts()).contains("PDF", "Лише віддалено", "clean.pdf");
    }

    @Test
    void exactGroupFormatterShowsHashTitlesFormatsAndBookIds() {
        String bookId = "11111111-1111-1111-1111-111111111111";
        var group = new ExactDuplicateGroup("abc123", List.of(
                new ExactDuplicateArtifact(BookId.fromString(bookId), "a", "Book A", "fb2",
                        new BookFile("a.fb2", "", "", 10, ""), 10),
                new ExactDuplicateArtifact(BookId.fromString("22222222-2222-2222-2222-222222222222"), "b", "Book B", "epub",
                        new BookFile("b.epub", "", "", 10, ""), 10)));

        assertThat(DuplicateReviewUiService.formatExactGroups(List.of(group)))
                .contains("abc123", "Book A", "FB2", "a.fb2", bookId, "Book B", "EPUB");
    }

    private static BookDto book(String id, String title, String authors, BookArtifactDto artifact) {
        return BookDto.builder().id(id).title(title).authorsText(authors).artifacts(List.of(artifact)).build();
    }

    private static BookArtifactDto artifact(String id, String format, String state, String fileName, boolean local) {
        return BookArtifactDto.builder().id(id).format(format).state(state).fileName(fileName).local(local).build();
    }
}
