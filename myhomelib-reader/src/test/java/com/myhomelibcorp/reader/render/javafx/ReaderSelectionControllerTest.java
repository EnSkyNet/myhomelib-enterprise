package com.myhomelibcorp.reader.render.javafx;

import com.myhomelibcorp.reader.api.BookMetadata;
import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReaderSelectionControllerTest {

    @Test
    void keyboardSelectionProducesDurableTextContext() {
        Fixture fixture = fixture(6);

        assertThat(fixture.controller().extendByCharacters(4)).isTrue();
        var selection = fixture.controller().snapshot().orElseThrow();

        assertThat(selection.startOffset()).isEqualTo(6);
        assertThat(selection.endOffset()).isEqualTo(10);
        assertThat(selection.text()).isEqualTo("beta");
        assertThat(selection.chapterId()).isEqualTo("chapter-1");
        assertThat(selection.chapterTitle()).isEqualTo("Chapter one");
        assertThat(selection.paragraphId()).isEqualTo("p:0");
        assertThat(selection.prefix()).isEqualTo("Alpha ");
        assertThat(selection.suffix()).isEqualTo(" gamma delta");
        assertThat(selection.position()).isBetween(0.0, 1.0);
    }

    @Test
    void visibleStartHandleCanBeDraggedWhileOppositeEndpointStaysFixed() {
        Fixture fixture = fixture(6);
        fixture.controller().extendByCharacters(4); // beta = [6,10]
        PageLayout page = PageLayout.builder()
                .startOffset(0)
                .endOffset(fixture.textLength())
                .width(220)
                .height(40)
                .lines(List.of(new LineLayout(
                        "Alpha beta gamma delta", 0, 5, 220, 20, 14,
                        0, 0, TextStyle.NORMAL, 0, fixture.textLength())))
                .build();

        double startHandleX = 220.0 * 6.0 / fixture.textLength();
        assertThat(fixture.controller().beginHandleDrag(startHandleX, 25, page, 0)).isTrue();
        fixture.controller().drag(0, 15, page, 0);
        fixture.controller().finish(0, 15, page, 0);

        var selection = fixture.controller().snapshot().orElseThrow();
        assertThat(selection.startOffset()).isZero();
        assertThat(selection.endOffset()).isEqualTo(10);
        assertThat(selection.text()).isEqualTo("Alpha beta");
    }

    @Test
    void blankOnlySelectionIsNotExposedAsAnnotationCandidate() {
        String value = "Alpha   beta";
        TextStorageImpl text = new TextStorageImpl();
        text.appendParagraph(value, TextStyle.NORMAL);
        var document = CompactReaderDocument.builder()
                .metadata(new BookMetadata("book", "Book", List.of(), "en", null, null,
                        List.of(), "", "", "", null, value.length()))
                .chapters(List.of(new ChapterIndex("chapter-1", "Chapter one", 0, value.length(), 1)))
                .text(text)
                .totalTextLength(value.length())
                .build();
        ReaderEngine engine = mock(ReaderEngine.class);
        when(engine.isOpen()).thenReturn(true);
        when(engine.getCurrentDocument()).thenReturn(document);
        when(engine.getCurrentPosition()).thenReturn(new ReaderPosition(0, 5, 0, 0));
        ReaderSelectionController controller = new ReaderSelectionController(engine, mock(JavaFxReaderRenderer.class));

        controller.extendByCharacters(3);

        assertThat(controller.snapshot()).isEmpty();
    }

    private static Fixture fixture(long currentOffset) {
        String value = "Alpha beta gamma delta";
        TextStorageImpl text = new TextStorageImpl();
        text.appendParagraph(value, TextStyle.NORMAL);
        var document = CompactReaderDocument.builder()
                .metadata(new BookMetadata("book", "Book", List.of(), "en", null, null,
                        List.of(), "", "", "", null, value.length()))
                .chapters(List.of(new ChapterIndex("chapter-1", "Chapter one", 0, value.length(), 1)))
                .text(text)
                .totalTextLength(value.length())
                .build();
        ReaderEngine engine = mock(ReaderEngine.class);
        when(engine.isOpen()).thenReturn(true);
        when(engine.getCurrentDocument()).thenReturn(document);
        when(engine.getCurrentPosition()).thenReturn(new ReaderPosition(0, currentOffset, 0, 0));
        return new Fixture(new ReaderSelectionController(engine, mock(JavaFxReaderRenderer.class)), value.length());
    }

    private record Fixture(ReaderSelectionController controller, int textLength) { }
}
