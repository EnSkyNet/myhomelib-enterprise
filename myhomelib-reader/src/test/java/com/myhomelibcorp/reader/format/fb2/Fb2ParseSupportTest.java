package com.myhomelibcorp.reader.format.fb2;

import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Fb2ParseSupportTest {
    @Test
    void normalizesWhitespaceAndPreservesInlineStyle() {
        TextStorageImpl storage = new TextStorageImpl();
        boolean trailingSpace = Fb2ParseSupport.appendNormalized(
                storage, "  hello\n\tworld  ", TextStyle.BOLD, false);

        assertThat(storage.getFullText()).isEqualTo(" hello world ");
        assertThat(trailingSpace).isTrue();
        assertThat(storage.getSpans(0, storage.length()))
                .allSatisfy(span -> assertThat(span.style()).isEqualTo(TextStyle.BOLD));
    }

    @Test
    void combinesAuthorAndNestedBoldItalicExactlyAsStreamingParserContract() {
        assertThat(Fb2ParseSupport.buildAuthor("Іван", "Іванович", "Тестовий", "nick"))
                .isEqualTo("Іван Іванович Тестовий");
        assertThat(Fb2ParseSupport.combineInlineStyles(TextStyle.BOLD, TextStyle.ITALIC))
                .isEqualTo(TextStyle.BOLD_ITALIC);
        assertThat(Fb2ParseSupport.cleanTitle("  Розділ\n   один ")).isEqualTo("Розділ один");
    }
    @Test
    void mapsFb2SemanticParagraphsWithoutCollapsingThemToGenericHeadings() {
        assertThat(Fb2ParseSupport.styleForParagraph(
                "p", true, 1, false, false, false, false, false))
                .isEqualTo(TextStyle.CHAPTER_TITLE);
        assertThat(Fb2ParseSupport.styleForParagraph(
                "p", true, 2, false, false, false, false, false))
                .isEqualTo(TextStyle.SECTION_TITLE);
        assertThat(Fb2ParseSupport.styleForParagraph(
                "p", false, 1, true, false, false, false, false))
                .isEqualTo(TextStyle.POEM);
        assertThat(Fb2ParseSupport.styleForParagraph(
                "p", false, 1, false, true, false, false, false))
                .isEqualTo(TextStyle.EPIGRAPH);
        assertThat(Fb2ParseSupport.styleForParagraph(
                "p", false, 1, false, false, true, false, false))
                .isEqualTo(TextStyle.CITE);
        assertThat(Fb2ParseSupport.styleForParagraph(
                "p", false, 1, false, false, false, true, false))
                .isEqualTo(TextStyle.ANNOTATION);
        assertThat(Fb2ParseSupport.styleForParagraph(
                "p", false, 1, false, false, false, false, true))
                .isEqualTo(TextStyle.FOOTNOTE);
        assertThat(Fb2ParseSupport.inlineStyleFor("strong")).isEqualTo(TextStyle.STRONG);
        assertThat(Fb2ParseSupport.inlineStyleFor("emphasis")).isEqualTo(TextStyle.EMPHASIS);
    }

}
