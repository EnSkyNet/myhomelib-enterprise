package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.book.BookArtifactState;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReaderArtifactFallbackTest {

    @Test
    void missingDerivedPreferredArtifactFallsBackToReadableOriginalWithoutPersistingPreference() {
        BookArtifact original = BookArtifact.builder()
                .id("original-fb2")
                .format("fb2")
                .local(true)
                .state(BookArtifactState.AVAILABLE)
                .file(new BookFile("books.zip", "", "story.fb2", 100, "/library"))
                .build();
        BookArtifact missingDerived = BookArtifact.builder()
                .id("derived-epub")
                .sourceId("conversion:epub")
                .format("epub")
                .local(true)
                .state(BookArtifactState.AVAILABLE)
                .file(new BookFile("generated.epub", "", "", 200, "/exports"))
                .build();
        Book book = Book.builder().title("Fallback")
                .file(missingDerived.getFile())
                .artifacts(List.of(original, missingDerived))
                .preferredArtifactId("derived-epub")
                .local(true)
                .build();

        BookResourcePort resources = mock(BookResourcePort.class);
        Path archive = Path.of("/library/books.zip");
        when(resources.locateBookContainer(any(Book.class))).thenAnswer(invocation -> {
            Book candidate = invocation.getArgument(0);
            return "original-fb2".equals(candidate.getPreferredArtifactId()) ? Optional.of(archive) : Optional.empty();
        });

        var selected = NewReaderWorkspaceController.resolveReadableReaderSource(book, null, resources).orElseThrow();

        assertThat(selected.artifactId()).isEqualTo("original-fb2");
        assertThat(selected.container()).isEqualTo(archive);
        assertThat(selected.book().getPreferredArtifactId()).isEqualTo("original-fb2");
        assertThat(selected.fallbackFromPreferred()).isTrue();
        assertThat(book.getPreferredArtifactId()).as("persistent/domain input remains unchanged").isEqualTo("derived-epub");
    }

    @Test
    void unreadableContentSearchArtifactFallsBackAndCallerCanSuppressForeignOffset() {
        BookArtifact original = BookArtifact.builder()
                .id("original")
                .format("fb2")
                .local(true)
                .state(BookArtifactState.AVAILABLE)
                .file(new BookFile("original.fb2", "", "", 10, "/library"))
                .build();
        BookArtifact indexed = BookArtifact.builder()
                .id("indexed")
                .format("epub")
                .local(true)
                .state(BookArtifactState.AVAILABLE)
                .file(new BookFile("missing.epub", "", "", 20, "/exports"))
                .build();
        Book book = Book.builder().title("Search fallback")
                .file(original.getFile())
                .artifacts(List.of(original, indexed))
                .preferredArtifactId("original")
                .local(true)
                .build();

        BookResourcePort resources = mock(BookResourcePort.class);
        Path originalPath = Path.of("/library/original.fb2");
        when(resources.locateBookContainer(any(Book.class))).thenAnswer(invocation -> {
            Book candidate = invocation.getArgument(0);
            return "original".equals(candidate.getPreferredArtifactId()) ? Optional.of(originalPath) : Optional.empty();
        });

        var selected = NewReaderWorkspaceController.resolveReadableReaderSource(book, "indexed", resources).orElseThrow();

        assertThat(selected.artifactId()).isEqualTo("original");
        assertThat(selected.container()).isEqualTo(originalPath);
    }
}
