package com.myhomelibcorp.domain.model.book;

import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BookMultiArtifactTest {

    @Test
    void copyBuilderPreservesAllFieldsAndDetachesRelationshipLists() {
        Book original = baseBook().withArtifacts(List.of(artifact("fb2", "book.fb2", "fb2")), "fb2");
        Book.Builder snapshot = original.toBuilder();
        original.addAuthor(new com.myhomelibcorp.domain.model.author.Author("Added", "", "Later"));
        Book copy = snapshot.title("Edited").build();

        assertThat(copy.getAuthors()).isEmpty();
        assertThat(original.getAuthors()).hasSize(1);
        assertThat(copy).usingRecursiveComparison().ignoringFields("title", "authors").isEqualTo(original);
        assertThat(copy.getArtifacts()).containsExactlyElementsOf(original.getArtifacts());
    }

    @Test
    void oneLogicalBookOwnsEpubFb2AndPdfAndCanSwitchPreferredArtifact() {
        BookArtifact epub = artifact("a-epub", "book.epub", "epub");
        BookArtifact fb2 = artifact("a-fb2", "book.fb2", "fb2");
        BookArtifact pdf = artifact("a-pdf", "book.pdf", "pdf");

        Book book = baseBook().withArtifacts(List.of(epub, fb2, pdf), epub.getId());

        assertThat(book.getArtifacts()).extracting(BookArtifact::getFormat)
                .containsExactly("epub", "fb2", "pdf");
        assertThat(book.getPreferredArtifact()).contains(epub);
        assertThat(book.getFileName()).isEqualTo("book.epub");

        Book selected = book.selectPreferredArtifact(pdf.getId());
        assertThat(selected.getPreferredArtifact()).contains(pdf);
        assertThat(selected.getFileName()).isEqualTo("book.pdf");
    }

    @Test
    void removingOneArtifactDoesNotRemoveLogicalBookOrOtherRepresentations() {
        Book book = baseBook().withArtifacts(List.of(
                artifact("a-epub", "book.epub", "epub"),
                artifact("a-fb2", "book.fb2", "fb2"),
                artifact("a-pdf", "book.pdf", "pdf")
        ), "a-fb2");

        Book afterRemoval = book.removeArtifact("a-fb2");

        assertThat(afterRemoval.getId()).isEqualTo(book.getId());
        assertThat(afterRemoval.isDeleted()).isFalse();
        assertThat(afterRemoval.getArtifacts()).extracting(BookArtifact::getId)
                .containsExactly("a-epub", "a-pdf");
        assertThat(afterRemoval.getPreferredArtifactId()).isEqualTo("a-epub");
        assertThat(afterRemoval.getFileName()).isEqualTo("book.epub");
    }

    @Test
    void artifactsAreImmutableFromOutsideAndUnknownPreferredArtifactIsRejected() {
        Book book = baseBook().withArtifacts(List.of(artifact("a-fb2", "book.fb2", "fb2")), "a-fb2");

        assertThatThrownBy(() -> book.getArtifacts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> book.selectPreferredArtifact("other"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    private static Book baseBook() {
        return Book.builder()
                .id(BookId.fromString("11111111-1111-1111-1111-111111111111"))
                .title("Multi-artifact")
                .metadata(BookMetadata.empty())
                .file(new BookFile("legacy.fb2", "books", "", 100, "/library"))
                .local(true)
                .build();
    }

    private static BookArtifact artifact(String id, String fileName, String format) {
        return BookArtifact.builder()
                .id(id)
                .name(fileName)
                .format(format)
                .file(new BookFile(fileName, "books", "", 100, "/library"))
                .local(true)
                .remote(false)
                .state(BookArtifactState.AVAILABLE)
                .build();
    }
}
