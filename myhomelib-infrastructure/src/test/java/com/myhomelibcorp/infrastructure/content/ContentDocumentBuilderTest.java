package com.myhomelibcorp.infrastructure.content;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ContentDocumentBuilderTest {
    @Test
    void enforcesGlobalBudgetIncludingParagraphAndChapterSeparators() throws Exception {
        var document = new ContentDocumentBuilder("s", "fb2", 5, 10);
        document.addParagraph("c1", "p1", "a");
        document.addParagraph("c1", "p2", "b");
        document.finishChapter("first");
        var buffer = document.paragraphBuffer();
        buffer.append('c');
        assertThrows(IOException.class, () -> buffer.append('d'));
        document.addParagraph("c2", "p3", buffer.text());
        assertThrows(IOException.class, () -> document.addParagraph("c2", "p4", "d"));
        document.finishChapter("second");
        var content = document.build();
        assertEquals("a\nb\nc", content.text());
        assertEquals(4, content.chapters().getLast().startOffset());
        assertEquals(5, content.anchors().getLast().endOffset());
    }
    @Test
    void boundsAnchorCountIndependentlyOfShortParagraphText() throws Exception {
        var document = new ContentDocumentBuilder("s", "txt", 100, 2);
        document.addParagraph("c", "p1", "a");
        document.addParagraph("c", "p2", "b");
        assertThrows(IOException.class, () -> document.addParagraph("c", "p3", "c"));
        document.finishChapter("");
        assertEquals("a\nb", document.build().text());
    }
    @Test
    void normalizesAcrossChunksWithoutRetainingWhitespaceRuns() throws Exception {
        var buffer = new NormalizedTextBuffer(4);
        for (String part : new String[]{"  а", "\t\n", "\u00a0", "б", "в   "}) {
            char[] chars = part.toCharArray();
            buffer.append(chars, 0, chars.length);
        }
        assertEquals("а бв", buffer.text());
        assertThrows(IOException.class, () -> buffer.append('г'));
        assertEquals("а бв", buffer.text());
        var empty = new NormalizedTextBuffer(0);
        for (int i = 0; i < 100_000; i++) empty.append(' ');
        assertTrue(empty.isEmpty());
    }
}
