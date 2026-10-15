package com.myhomelibcorp.application.content;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ExtractedContentTest {
    @Test
    void validatesUnicodeAndEmptyChaptersWithoutChangingOffsets() {
        var chapters = List.of(chapter("a", 0, "ї😀"), chapter("b", 4, ""), chapter("c", 5, "кінець"));
        var content = new ExtractedContent("source", "fb2", "ї😀\n\nкінець", chapters);
        assertEquals(11, content.textLength());
        assertEquals(5, content.chapters().getLast().startOffset());
    }
    @Test
    void rejectsMismatchedTextSeparatorsAndTrailingCharacters() {
        var chapters = List.of(chapter("a", 0, "abc"), chapter("b", 4, "def"));
        for (String invalid : List.of("abc def", "abx\ndef", "abc\nde", "abc\ndefx")) {
            assertThrows(IllegalArgumentException.class,
                    () -> new ExtractedContent("s", "txt", invalid, chapters), invalid);
        }
    }
    @Test
    void rejectsNoncontiguousAndOutOfRangeOffsets() {
        assertThrows(IllegalArgumentException.class, () -> new ExtractedContent("s", "txt", "abc\ndef",
                List.of(chapter("a", 0, "abc"), chapter("b", 3, "def"))));
        assertThrows(IllegalArgumentException.class, () -> new ExtractedContent("s", "txt", "abc",
                List.of(chapter("a", 0, "abcdef"))));
        assertThrows(IllegalArgumentException.class, () -> new ExtractedContent("s", "txt", "",
                List.of(chapter("a", Long.MAX_VALUE - 3, "abc"))));
    }
    @Test
    void preservesLegacyUnstructuredTextContract() {
        assertEquals("text", new ExtractedContent("s", "txt", "text", List.of()).text());
    }
    private static ExtractedChapter chapter(String id, long start, String text) {
        return new ExtractedChapter(id, "", start, start + text.length(), text, List.of());
    }
}
