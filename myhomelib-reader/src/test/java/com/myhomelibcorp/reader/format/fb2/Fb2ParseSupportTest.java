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
}
