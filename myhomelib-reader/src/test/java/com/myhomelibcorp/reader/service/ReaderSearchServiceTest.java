package com.myhomelibcorp.reader.service;

import com.myhomelibcorp.reader.api.BookMetadata;
import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.api.TextStorage;
import com.myhomelibcorp.reader.api.ParagraphInfo;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.document.DefaultTableOfContents;
import com.myhomelibcorp.reader.core.resource.SimpleResourceRepository;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReaderSearchServiceTest {
    private final ReaderSearchService service = new ReaderSearchService();

    @Test
    void caseInsensitiveSearchPreservesOriginalOffsetAfterLengthChangingUnicodeCaseMapping() {
        String value = "AİB tail";
        var document = document(value);

        var results = service.search(document, "b");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().textOffset()).isEqualTo(value.indexOf('B'));
        assertThat(service.searchPositions(document, "b").getFirst().textOffset())
                .isEqualTo(value.indexOf('B'));
    }

    @Test
    void unicodeCaseInsensitiveSearchMatchesDottedCapitalIWithoutChangingCoordinates() {
        String value = "xİy";
        var document = document(value);

        var results = service.search(document, "i");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().textOffset()).isEqualTo(1);
    }

    @Test
    void boundedSearchWindowDoesNotOverflowNearMaximumTextOffset() {
        TextStorage storage = mock(TextStorage.class);
        ReaderDocument document = mock(ReaderDocument.class);
        int paragraphStart = Integer.MAX_VALUE - 5;
        when(document.text()).thenReturn(storage);
        when(storage.length()).thenReturn(Integer.MAX_VALUE);
        when(storage.getParagraphs()).thenReturn(List.of(ParagraphInfo.of(paragraphStart, 0)));
        when(storage.getText(anyInt(), anyInt())).thenAnswer(invocation -> {
            int start = invocation.getArgument(0);
            int end = invocation.getArgument(1);
            if (start < 0 || end < start) throw new IllegalArgumentException("invalid range " + start + ".." + end);
            return "abcde".substring(0, Math.min(5, end - start));
        });

        assertThatCode(() -> service.search(document, "a"))
                .doesNotThrowAnyException();
        assertThat(service.search(document, "a").getFirst().textOffset()).isEqualTo(paragraphStart);
    }

    @Test
    void unicodeCaseSearchPreservesOffsetsAcrossCombiningAndSupplementaryCharacters() {
        String value = "😀Cafe\u0301 ς B";
        var document = document(value);

        var sigma = service.search(document, "Σ");
        var letter = service.search(document, "b");

        assertThat(sigma).singleElement().satisfies(hit ->
                assertThat(hit.textOffset()).isEqualTo(value.indexOf('ς')));
        assertThat(letter).singleElement().satisfies(hit ->
                assertThat(hit.textOffset()).isEqualTo(value.indexOf('B')));
    }

    @Test
    void queryIsTreatedAsLiteralNotAsRegex() {
        String value = "prefix a.b suffix acb";
        var document = document(value);

        var results = service.search(document, "a.b");

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().textOffset()).isEqualTo(value.indexOf("a.b"));
    }

    private static CompactReaderDocument document(String value) {
        TextStorageImpl text = new TextStorageImpl();
        text.appendParagraph(value, TextStyle.NORMAL);
        return CompactReaderDocument.builder()
                .metadata(new BookMetadata("book", "Book", List.of(), "en", null, null,
                        List.of(), "", "", "", null, value.length()))
                .chapters(List.of(new ChapterIndex("c1", "Chapter", 0, value.length(), 1)))
                .resources(new SimpleResourceRepository())
                .text(text)
                .toc(new DefaultTableOfContents())
                .totalTextLength(value.length())
                .build();
    }
}
