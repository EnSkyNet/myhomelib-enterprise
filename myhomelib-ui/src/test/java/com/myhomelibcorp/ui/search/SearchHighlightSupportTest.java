package com.myhomelibcorp.ui.search;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchHighlightSupportTest {
    @Test
    void preservesOriginalOffsetsAfterLengthChangingUnicodeCaseMapping() {
        var segments = SearchHighlightSupport.segments("AİB tail", "b");

        assertThat(segments).extracting(SearchHighlightSupport.Segment::text)
                .containsExactly("Aİ", "B", " tail");
        assertThat(segments).extracting(SearchHighlightSupport.Segment::highlighted)
                .containsExactly(false, true, false);
    }

    @Test
    void matchesUnicodeCaseInsensitively() {
        var segments = SearchHighlightSupport.segments("xİy", "i");

        assertThat(segments).extracting(SearchHighlightSupport.Segment::text)
                .containsExactly("x", "İ", "y");
        assertThat(segments.get(1).highlighted()).isTrue();
    }

    @Test
    void treatsSearchTextAsLiteral() {
        var segments = SearchHighlightSupport.segments("a.b acb", "a.b");

        assertThat(segments).extracting(SearchHighlightSupport.Segment::text)
                .containsExactly("a.b", " acb");
        assertThat(segments.getFirst().highlighted()).isTrue();
        assertThat(segments.getLast().highlighted()).isFalse();
    }
}
