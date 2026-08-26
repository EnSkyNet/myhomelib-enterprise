package com.myhomelibcorp.reader.layout;

import com.myhomelibcorp.reader.api.BookMetadata;
import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.document.DefaultTableOfContents;
import com.myhomelibcorp.reader.core.resource.SimpleResourceRepository;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextLayoutEngineTest {

    @Test
    void continuesLongParagraphFromPreviousPageEndInsteadOfRepeatingIt() {
        TextStorageImpl text = new TextStorageImpl();
        text.startParagraph(TextStyle.NORMAL);
        text.append(("Це довгий абзац для тестування посторінкового переносу. ").repeat(120), TextStyle.NORMAL);
        text.endParagraph();

        var document = CompactReaderDocument.builder()
                .metadata(new BookMetadata("id", "Book", List.of("Author"), "uk", null, null,
                        List.of(), "", "", "", null, text.length()))
                .chapters(List.of(new ChapterIndex("c1", "Chapter", 0, text.length(), 1)))
                .resources(new SimpleResourceRepository())
                .text(text)
                .toc(new DefaultTableOfContents())
                .totalTextLength(text.length())
                .build();

        ReaderSettings settings = ReaderSettings.defaultSettings();
        TextLayoutEngine engine = new TextLayoutEngine(new FontMetricsProviderImpl(settings), settings);
        PageDimensions dimensions = new PageDimensions(420, 320, 30, 30, 20, 20);

        var first = engine.layoutPage(document, 0, dimensions);
        var second = engine.layoutPage(document, first.getEndOffset(), dimensions);

        assertThat(first.isEmpty()).isFalse();
        assertThat(first.getEndOffset()).isGreaterThan(0).isLessThan(text.length());
        assertThat(second.isEmpty()).isFalse();
        assertThat(second.getStartOffset()).isEqualTo(first.getEndOffset());
        assertThat(second.getEndOffset()).isGreaterThan(first.getEndOffset());
        assertThat(second.getLines().getFirst().textOffset()).isGreaterThanOrEqualTo(first.getEndOffset());
    }
    @Test
    void usesLanguageAwareHyphenationWithoutChangingSourceOffsets() {
        TextStorageImpl text = new TextStorageImpl();
        text.startParagraph(TextStyle.NORMAL);
        text.append("бібліотека", TextStyle.NORMAL);
        text.endParagraph();

        var document = CompactReaderDocument.builder()
                .metadata(new BookMetadata("hy", "Hyphen", List.of("Author"), "uk", null, null,
                        List.of(), "", "", "", null, text.length()))
                .chapters(List.of(new ChapterIndex("c1", "Chapter", 0, text.length(), 1)))
                .resources(new SimpleResourceRepository()).text(text).toc(new DefaultTableOfContents())
                .totalTextLength(text.length()).build();

        ReaderSettings settings = ReaderSettings.defaultSettings();
        TextLayoutEngine engine = new TextLayoutEngine(new FontMetricsProviderImpl(settings), settings);
        var page = engine.layoutPage(document, 0, new PageDimensions(95, 240, 10, 10, 10, 10));

        assertThat(page.getLines()).anySatisfy(line -> assertThat(line.text()).contains("‐"));
        assertThat(page.getEndOffset()).isLessThanOrEqualTo(text.length());
        assertThat(text.getFullText()).doesNotContain("‐");
    }

    @Test
    void keepsInlineStylesAsCompactRuns() {
        TextStorageImpl text = new TextStorageImpl();
        text.startParagraph(TextStyle.NORMAL);
        text.append("Звичайний ", TextStyle.NORMAL);
        text.append("жирний", TextStyle.BOLD);
        text.append(" та ", TextStyle.NORMAL);
        text.append("курсив", TextStyle.ITALIC);
        text.endParagraph();

        var document = CompactReaderDocument.builder()
                .metadata(new BookMetadata("id2", "Styled", List.of("Author"), "uk", null, null,
                        List.of(), "", "", "", null, text.length()))
                .chapters(List.of(new ChapterIndex("c1", "Chapter", 0, text.length(), 1)))
                .resources(new SimpleResourceRepository())
                .text(text)
                .toc(new DefaultTableOfContents())
                .totalTextLength(text.length())
                .build();

        ReaderSettings settings = ReaderSettings.defaultSettings();
        TextLayoutEngine engine = new TextLayoutEngine(new FontMetricsProviderImpl(settings), settings);
        var page = engine.layoutPage(document, 0, new PageDimensions(800, 400, 30, 30, 20, 20));

        assertThat(page.isEmpty()).isFalse();
        assertThat(page.getLines().getFirst().runs()).isNotEmpty();
        assertThat(page.getLines().getFirst().runs())
                .extracting(run -> run.style())
                .contains(TextStyle.BOLD, TextStyle.ITALIC);
    }

}
