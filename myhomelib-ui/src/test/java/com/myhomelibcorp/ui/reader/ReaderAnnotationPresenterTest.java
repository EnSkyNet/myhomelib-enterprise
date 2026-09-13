package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.annotation.AnnotationAnchorData;
import com.myhomelibcorp.application.annotation.AnnotationReaderItem;
import com.myhomelibcorp.reader.api.BookMetadata;
import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.api.ReaderSelection;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderAnnotationPresenterTest {

    @Test
    void selectionIsConvertedToRendererIndependentApplicationAnchor() {
        ReaderSelection selection = new ReaderSelection(
                4, 9, "quote", 2, "c2", "Second", 7, "p:7", 0.4, "pre", "post");

        AnnotationAnchorData anchor = ReaderAnnotationPresenter.anchor("book-1", "artifact-1", selection);

        assertThat(anchor.bookId()).isEqualTo("book-1");
        assertThat(anchor.artifactId()).isEqualTo("artifact-1");
        assertThat(anchor.chapterId()).isEqualTo("c2");
        assertThat(anchor.paragraphId()).isEqualTo("p:7");
        assertThat(anchor.startOffset()).isEqualTo(4);
        assertThat(anchor.endOffset()).isEqualTo(9);
        assertThat(anchor.quote()).isEqualTo("quote");
        assertThat(anchor.prefix()).isEqualTo("pre");
        assertThat(anchor.suffix()).isEqualTo("post");
    }

    @Test
    void persistedAnchorRelocatesByQuoteContextAndWrongArtifactIsHidden() {
        String textValue = "zero quote x quote tail";
        var document = document(textValue);
        AnnotationReaderItem relocated = new AnnotationReaderItem(
                "a1",
                new AnnotationAnchorData("book", "artifact-1", "c1", "Chapter", "p:0",
                        0, 5, 0.60, "quote", "x ", " tail"),
                "#FFF59D", false);
        AnnotationReaderItem otherArtifact = new AnnotationReaderItem(
                "a2",
                new AnnotationAnchorData("book", "artifact-2", "c1", "Chapter", "p:0",
                        5, 10, 0.20, "quote", "zero ", " x"),
                "#FFFF00", false);

        var overlays = ReaderAnnotationPresenter.overlays(
                List.of(relocated, otherArtifact), "artifact-1", document);

        assertThat(overlays).hasSize(1);
        assertThat(overlays.getFirst().id()).isEqualTo("a1");
        assertThat(overlays.getFirst().startOffset()).isEqualTo(textValue.lastIndexOf("quote"));
        assertThat(overlays.getFirst().endOffset()).isEqualTo(textValue.lastIndexOf("quote") + "quote".length());
    }

    @Test
    void exactTextOffsetsRemainStableIndependentOfPagination() {
        String textValue = "first second third";
        var document = document(textValue);
        int start = textValue.indexOf("second");
        AnnotationReaderItem annotation = new AnnotationReaderItem(
                "a1",
                new AnnotationAnchorData("book", null, "c1", "Chapter", "p:0",
                        start, start + 6, 0.3, "second", "first ", " third"),
                "#FFF59D", false);

        var overlay = ReaderAnnotationPresenter.overlays(List.of(annotation), null, document).getFirst();

        assertThat(overlay.startOffset()).isEqualTo(start);
        assertThat(overlay.endOffset()).isEqualTo(start + 6);
    }

    private static CompactReaderDocument document(String value) {
        TextStorageImpl text = new TextStorageImpl();
        text.appendParagraph(value, TextStyle.NORMAL);
        return CompactReaderDocument.builder()
                .metadata(new BookMetadata("book", "Book", List.of(), "en", null, null,
                        List.of(), "", "", "", null, value.length()))
                .chapters(List.of(new ChapterIndex("c1", "Chapter", 0, value.length(), 1)))
                .text(text)
                .totalTextLength(value.length())
                .build();
    }
}
