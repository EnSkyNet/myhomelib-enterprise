package com.myhomelibcorp.ui.search;

import com.myhomelibcorp.application.annotation.AnnotationManagerItem;
import com.myhomelibcorp.application.annotation.AnnotationManagerType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchAnnotationPresentationTest {

    @Test
    void summaryPreservesExistingPriorityWhitespaceAndChapterFallback() {
        AnnotationManagerItem note = item(AnnotationManagerType.NOTE, "  note\n  text  ", "quote", "Chapter", List.of());
        AnnotationManagerItem chapterOnly = item(AnnotationManagerType.HIGHLIGHT, "", "", "Chapter 2", List.of());

        assertThat(SearchAnnotationPresentation.summary(note)).isEqualTo("note text");
        assertThat(SearchAnnotationPresentation.summary(chapterOnly)).isEqualTo("Chapter 2");
    }

    @Test
    void summaryTruncationNeverSplitsSurrogatePair() {
        String note = "a".repeat(136) + "\uD83D\uDE00" + "tail";
        AnnotationManagerItem item = item(AnnotationManagerType.NOTE, note, "", "", List.of());

        assertThat(SearchAnnotationPresentation.summary(item))
                .isEqualTo("a".repeat(136) + "…");
    }

    @Test
    void summaryTruncationDoesNotDetachCombiningMarkFromBaseCharacter() {
        String note = "a".repeat(136) + "e\u0301" + "tail";
        AnnotationManagerItem item = item(AnnotationManagerType.NOTE, note, "", "", List.of());

        assertThat(SearchAnnotationPresentation.summary(item))
                .isEqualTo("a".repeat(136) + "…");
    }

    @Test
    void detailsPreserveChapterQuoteDistinctNoteAndTagsOrder() {
        AnnotationManagerItem item = item(
                AnnotationManagerType.NOTE, "My note", "Quoted text", "Chapter", List.of("tag-a", "tag-b"));

        assertThat(SearchAnnotationPresentation.details(item))
                .isEqualTo("Chapter\nQuoted text\nMy note\ntag-a, tag-b");
    }

    private static AnnotationManagerItem item(
            AnnotationManagerType type, String note, String quote, String chapter, List<String> tags) {
        Instant now = Instant.parse("2026-09-27T12:00:00Z");
        return new AnnotationManagerItem(
                "a1", "b1", "Book", type, "#fff", note, tags, chapter, quote, 0.5, now, now);
    }
}
