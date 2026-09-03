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
    void traversalAndAbsoluteArchiveEntriesRemainRejected() {
        assertThat(ArchiveImportSupport.isSafeEntryName("../book.fb2")).isFalse();
        assertThat(ArchiveImportSupport.isSafeEntryName("books/../book.fb2")).isFalse();
        assertThat(ArchiveImportSupport.isSafeEntryName("/books/book.fb2")).isFalse();
        assertThat(ArchiveImportSupport.isSafeEntryName("C:/books/book.fb2")).isFalse();
        assertThat(ArchiveImportSupport.isSafeEntryName("books/good.fb2")).isTrue();
    }
}
