package com.myhomelibcorp.reader.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReaderStyleSheetTest {
    @Test
    void mapsSemanticTextStylesToIndependentReaderElements() {
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.CHAPTER_TITLE)).isEqualTo(ReaderSemanticElement.CHAPTER_TITLE);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.SECTION_TITLE)).isEqualTo(ReaderSemanticElement.SECTION_TITLE);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.SUBTITLE)).isEqualTo(ReaderSemanticElement.SUBTITLE);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.EPIGRAPH)).isEqualTo(ReaderSemanticElement.EPIGRAPH);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.CITE)).isEqualTo(ReaderSemanticElement.QUOTE);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.VERSE)).isEqualTo(ReaderSemanticElement.POEM);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.POEM_AUTHOR)).isEqualTo(ReaderSemanticElement.POEM_AUTHOR);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.TEXT_AUTHOR)).isEqualTo(ReaderSemanticElement.TEXT_AUTHOR);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.ANNOTATION)).isEqualTo(ReaderSemanticElement.ANNOTATION);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.LINK)).isEqualTo(ReaderSemanticElement.LINK);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.FOOTNOTE)).isEqualTo(ReaderSemanticElement.FOOTNOTE);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.STRONG)).isEqualTo(ReaderSemanticElement.STRONG);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.EMPHASIS)).isEqualTo(ReaderSemanticElement.EMPHASIS);
        assertThat(ReaderStyleSheet.semanticElement(TextStyle.CODE)).isEqualTo(ReaderSemanticElement.CODE);
    }

    @Test
    void validatesInvalidStyleValuesAndMergesOverridesWithDefaults() {
        ReaderElementStyle override = new ReaderElementStyle(
                "  Arial  ", -1.0, Double.NaN, "bold italic", "not-a-color", "center", -2.0, 12.0);
        ReaderStyleSheet sheet = ReaderStyleSheet.withOverrides(Map.of(ReaderSemanticElement.QUOTE, override));

        ReaderElementStyle quote = sheet.styles().get(ReaderSemanticElement.QUOTE);
        assertThat(quote.fontFamily()).isEqualTo("Arial");
        assertThat(quote.fontSize()).isNull();
        assertThat(quote.fontScale()).isEqualTo(1.0);
        assertThat(quote.color()).isEmpty();
        assertThat(quote.alignment()).isEqualTo("center");
        assertThat(quote.spacingBefore()).isNull();
        assertThat(quote.spacingAfter()).isEqualTo(12.0);
        assertThat(sheet.styles()).containsKey(ReaderSemanticElement.BOOK_TITLE);
    }
}
