package com.myhomelibcorp.infrastructure.importer.archive;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArchiveImportSupportTest {

    @Test
    void unicodeEntryNamesDoNotRequireHostFilesystemEncoding() {
        String name = "Книги/08 - Моё пространственное королевство. Том 8.fb2";

        assertThat(ArchiveImportSupport.isSafeEntryName(name)).isTrue();
        assertThat(ArchiveImportSupport.fileName(name))
                .isEqualTo("08 - Моё пространственное королевство. Том 8.fb2");
        assertThat(ArchiveImportSupport.suffixFor(name)).isEqualTo(".fb2");
        assertThat(ArchiveImportSupport.importerProbePath(name).toString()).isEqualTo("archive-entry.fb2");
    }

    @Test
    void legacyHashUnicodeEntryKeepsRawIdentityButGetsReadableDisplayFileName() {
        String raw = "books/#U0412#U043e#U0437#U0432#U0440#U0430#U0449#U0435#U043d#U0438#U0435_1.fb2";

        assertThat(ArchiveImportSupport.isSafeEntryName(raw)).isTrue();
        assertThat(ArchiveImportSupport.fileName(raw)).isEqualTo("Возвращение_1.fb2");
        assertThat(ArchiveImportSupport.suffixFor(raw)).isEqualTo(".fb2");
    }

    @Test
    void traversalAndAbsoluteArchiveEntriesRemainRejected() {
        assertThat(ArchiveImportSupport.isSafeEntryName("../book.fb2")).isFalse();
        assertThat(ArchiveImportSupport.isSafeEntryName("books/../book.fb2")).isFalse();
        assertThat(ArchiveImportSupport.isSafeEntryName("/books/book.fb2")).isFalse();
        assertThat(ArchiveImportSupport.isSafeEntryName("C:/books/book.fb2")).isFalse();
        assertThat(ArchiveImportSupport.isSafeEntryName("books/good.fb2")).isTrue();
    }
}
