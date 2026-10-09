package com.myhomelibcorp.epub.optimizer.graph;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EpubReferencePathsTest {
    @Test
    void resolvesAndRelativizesInternalReferencesWithoutEscapingRoot() {
        assertThat(EpubReferencePaths.resolveReference("OEBPS/text", "../images/a.png?x=1#cover"))
                .isEqualTo("OEBPS/images/a.png");
        assertThat(EpubReferencePaths.relative("OEBPS/text", "OEBPS/images/a.png"))
                .isEqualTo("../images/a.png");
        assertThat(EpubReferencePaths.resolve("", "../../escape.png")).isEmpty();
    }

    @Test
    void parentAndSuffixHandlingAreNullSafeAndDeterministic() {
        assertThat(EpubReferencePaths.parent("OEBPS/content.opf")).isEqualTo("OEBPS");
        assertThat(EpubReferencePaths.parent("content.opf")).isEmpty();
        assertThat(EpubReferencePaths.stripSuffix("a.xhtml#part?ignored")).isEqualTo("a.xhtml");
        assertThat(EpubReferencePaths.stripSuffix(null)).isEmpty();
    }
}
