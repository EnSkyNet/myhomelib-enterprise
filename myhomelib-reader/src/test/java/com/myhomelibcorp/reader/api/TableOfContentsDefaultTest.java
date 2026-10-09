package com.myhomelibcorp.reader.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableOfContentsDefaultTest {
    @Test
    void nullEntriesBehaveAsEmptyContents() {
        TableOfContents toc = () -> null;

        assertThat(toc.isEmpty()).isTrue();
        assertThat(toc.size()).isZero();
        assertThat(toc.findEntryAt(0)).isNull();
    }

    @Test
    void findEntryAtSkipsNullEntries() {
        TocEntry chapter = new TocEntry("Chapter", 100, 0);
        TableOfContents toc = () -> java.util.Arrays.asList(null, chapter);

        assertThat(toc.findEntryAt(120)).isSameAs(chapter);
    }
}
