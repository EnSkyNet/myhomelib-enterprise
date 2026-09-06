package com.myhomelibcorp.domain.model.valueobject;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookFilePathTest {
    @Test
    void preservesFileNameAcrossRelativeAbsoluteRootUnicodeAndWindowsPaths() {
        assertThat(book("книга.fb2", "", "").getFullPath()).isEqualTo("книга.fb2");
        assertThat(book("book.epub", "authors/asimov", "").getFullPath()).isEqualTo("authors/asimov/book.epub");
        assertThat(book("book.pdf", "/srv/books", "/ignored").getFullPath()).isEqualTo("/srv/books/book.pdf");
        assertThat(book("книга.fb2", "Українські/Фантастика", "/library").getFullPath())
                .isEqualTo("/library/Українські/Фантастика/книга.fb2");
        assertThat(book("book.fb2", "SciFi", "C:\\Library").getFullPath())
                .isEqualTo("C:\\Library\\SciFi\\book.fb2");
        assertThat(book("book.fb2", "\\\\server\\share\\Books", "C:\\ignored").getFullPath())
                .isEqualTo("\\\\server\\share\\Books\\book.fb2");
    }

    @Test
    void absoluteFileNameWinsAndIsNeverDropped() {
        assertThat(book("C:\\Library\\book.fb2", "ignored", "/ignored").getFullPath())
                .isEqualTo("C:\\Library\\book.fb2");
        assertThat(book("/library/book.fb2", "ignored", "/ignored").getFullPath())
                .isEqualTo("/library/book.fb2");
    }

    private static BookFile book(String fileName, String folder, String root) {
        return new BookFile(fileName, folder, "", 1, root);
    }
}
