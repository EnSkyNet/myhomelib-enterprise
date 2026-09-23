package com.myhomelibcorp.infrastructure.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveEntryNameSupportTest {

    @Test
    void matchesLegacyHashUnicodeArchiveNameToReadableCatalogPath() {
        assertThat(ArchiveEntryNameSupport.sameLogicalPath(
                "books/#U0412#U043e#U0437#U0432#U0440#U0430#U0449#U0435#U043d#U0438#U0435_1.fb2",
                "books/Возвращение_1.fb2"))
                .isTrue();
    }

    @Test
    void doesNotTreatAsciiHashEscapeAsPathSyntax() {
        assertThat(ArchiveEntryNameSupport.sameLogicalPath("books/#U002Fbook.fb2", "books//book.fb2"))
                .isFalse();
    }
}
