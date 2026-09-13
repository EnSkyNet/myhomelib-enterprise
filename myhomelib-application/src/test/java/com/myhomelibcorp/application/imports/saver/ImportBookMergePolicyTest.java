package com.myhomelibcorp.application.imports.saver;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImportBookMergePolicyTest {
    @Test
    void refreshingMetadataRetainsEveryArtifactAndThePreferredFormat() {
        var fb2 = BookArtifact.builder().id("fb2").name("book.fb2").format("fb2")
                .file(new BookFile("book.fb2", "", "", 123, "/library")).build();
        var epub = BookArtifact.builder().id("epub").name("book.epub").format("epub")
                .file(new BookFile("book.epub", "", "", 456, "/library")).build();
        Book existing = Book.builder().title("Old").local(true)
                .metadata(BookMetadata.builder().rate(5).progress(85).review("My review").build())
                .build().withArtifacts(List.of(fb2, epub), "epub");
        Book result = ImportBookMergePolicy.mergePreservingUserState(existing, Book.builder().title("New").build());
        assertThat(result.getTitle()).isEqualTo("New");
        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getArtifacts()).containsExactly(fb2, epub);
        assertThat(result.getPreferredArtifactId()).isEqualTo("epub");
        assertThat(result.getFile()).isSameAs(epub.getFile());
        assertThat(result.getProgress()).isEqualTo(85);
        assertThat(result.getRate()).isEqualTo(5);
        assertThat(result.getReview()).isEqualTo("My review");
    }
}
