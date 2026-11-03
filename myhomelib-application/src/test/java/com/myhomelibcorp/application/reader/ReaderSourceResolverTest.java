package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.book.BookArtifactState;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReaderSourceResolverTest {
    @TempDir Path tempDir;

    @Test
    void missingDerivedPreferredArtifactFallsBackToReadableOriginalWithoutMutation() {
        BookArtifact original = BookArtifact.builder()
                .id("original-fb2").format("fb2").local(true).state(BookArtifactState.AVAILABLE)
                .file(new BookFile("books.zip", "", "story.fb2", 100, tempDir.toString())).build();
        BookArtifact missingDerived = BookArtifact.builder()
                .id("derived-epub").sourceId("conversion:epub").format("epub").local(true)
                .state(BookArtifactState.AVAILABLE)
                .file(new BookFile("generated.epub", "", "", 200, tempDir.resolve("exports").toString())).build();
        Book book = Book.builder().title("Fallback").file(missingDerived.getFile())
                .artifacts(List.of(original, missingDerived)).preferredArtifactId("derived-epub").local(true).build();
        BookResourcePort resources = mock(BookResourcePort.class);
        Path archive = tempDir.resolve("books.zip");
        when(resources.locateBookContainer(any(Book.class))).thenAnswer(invocation -> {
            Book candidate = invocation.getArgument(0);
            return "original-fb2".equals(candidate.getPreferredArtifactId()) ? Optional.of(archive) : Optional.empty();
        });

        ReaderSourceResolver.SourceChoice selected = ReaderSourceResolver.resolve(book, null, resources).orElseThrow();

        assertThat(selected.artifactId()).isEqualTo("original-fb2");
        assertThat(selected.container()).isEqualTo(archive);
        assertThat(selected.fallbackFromPreferred()).isTrue();
        assertThat(book.getPreferredArtifactId()).isEqualTo("derived-epub");
    }

    @Test
    void selectedArchiveEntryUsesBoundedPortMaterialization() throws Exception {
        Path archive = tempDir.resolve("bundle.zip");
        Book book = Book.builder().title("Archive")
                .file(new BookFile("bundle.zip", "", "inside.fb2", 12, tempDir.toString()))
                .local(true).build();
        BookResourcePort resources = mock(BookResourcePort.class);
        when(resources.isArchive(archive.toString())).thenReturn(true);
        when(resources.materializeArchiveBookEntry(any(Book.class), any(Path.class), any(Path.class), anyLong(), any()))
                .thenAnswer(invocation -> {
                    Path target = invocation.getArgument(2);
                    Files.writeString(target, "book");
                    return Optional.of("inside.fb2");
                });

        ReaderSourceResolver.MaterializedSource materialized =
                ReaderSourceResolver.materialize(book, archive, resources, "missing archive entry");
        try {
            assertThat(materialized.readerPath()).isEqualTo(materialized.temporaryPath());
            assertThat(materialized.readerPath()).isNotEqualTo(archive);
            assertThat(Files.readString(materialized.readerPath())).isEqualTo("book");
            verify(resources).materializeArchiveBookEntry(
                    any(Book.class), org.mockito.ArgumentMatchers.eq(archive), any(Path.class),
                    org.mockito.ArgumentMatchers.eq(ArchiveSafetyLimits.MAX_ENTRY_BYTES), any());
        } finally {
            Files.deleteIfExists(materialized.temporaryPath());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {".7z", ".rar"})
    void wholeArchiveBecomesEphemeralReaderZipWithMultipleBookEntries(String suffix) throws Exception {
        Path source = Files.writeString(tempDir.resolve("multi" + suffix), "mock-container");
        Book book = Book.builder().title("Multi-book archive")
                .file(new BookFile(source.getFileName().toString(), "", "", 24,
                        tempDir.toString()))
                .local(true).build();
        BookResourcePort resources = mock(BookResourcePort.class);
        when(resources.isArchive(source.toString())).thenReturn(true);
        when(resources.listArchiveEntries(source)).thenReturn(List.of(
                "author/first.fb2", "second.txt", "cover.jpg"));
        when(resources.readArchiveEntry(source, "author/first.fb2")).thenAnswer(call ->
                Optional.of(new java.io.ByteArrayInputStream("first".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        when(resources.readArchiveEntry(source, "second.txt")).thenAnswer(call ->
                Optional.of(new java.io.ByteArrayInputStream("second".getBytes(java.nio.charset.StandardCharsets.UTF_8))));

        ReaderSourceResolver.MaterializedSource prepared =
                ReaderSourceResolver.materialize(book, source, resources, "missing");
        try {
            assertThat(prepared.temporaryPath()).isEqualTo(prepared.readerPath());
            assertThat(prepared.readerPath()).isNotEqualTo(source);
            assertThat(prepared.readerPath().toString()).endsWith(".zip");
            try (var zip = new java.util.zip.ZipFile(prepared.readerPath().toFile())) {
                assertThat(zip.size()).isEqualTo(2);
                assertThat(zip.getEntry("author/first.fb2")).isNotNull();
                try (var in = zip.getInputStream(zip.getEntry("second.txt"))) {
                    assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                            .isEqualTo("second");
                }
            }
        } finally {
            Files.deleteIfExists(prepared.temporaryPath());
        }
    }

    @Test
    void unsafeArchiveMemberNameCannotBeRepackagedForReader() throws Exception {
        Path source = Files.writeString(tempDir.resolve("unsafe.rar"), "mock-container");
        Book book = Book.builder().title("Unsafe archive")
                .file(new BookFile("unsafe.rar", "", "", 24, tempDir.toString()))
                .local(true).build();
        BookResourcePort resources = mock(BookResourcePort.class);
        when(resources.isArchive(source.toString())).thenReturn(true);
        when(resources.listArchiveEntries(source)).thenReturn(List.of("../escape.fb2"));

        assertThatThrownBy(() -> ReaderSourceResolver.materialize(book, source, resources, "missing"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Unsafe archive member name");
    }

    @Test
    void failedArchiveMaterializationDeletesStagingTemp() throws Exception {
        Path archive = tempDir.resolve("bundle.zip");
        Book book = Book.builder().title("Archive")
                .file(new BookFile("bundle.zip", "", "inside.fb2", 12, tempDir.toString()))
                .local(true).build();
        BookResourcePort resources = mock(BookResourcePort.class);
        when(resources.isArchive(archive.toString())).thenReturn(true);
        AtomicReference<Path> target = new AtomicReference<>();
        when(resources.materializeArchiveBookEntry(any(Book.class), any(Path.class), any(Path.class), anyLong(), any()))
                .thenAnswer(invocation -> {
                    target.set(invocation.getArgument(2));
                    return Optional.empty();
                });

        assertThatThrownBy(() -> ReaderSourceResolver.materialize(book, archive, resources, "missing archive entry"))
                .isInstanceOf(IOException.class).hasMessage("missing archive entry");
        assertThat(target.get()).isNotNull();
        assertThat(Files.exists(target.get())).isFalse();
    }
}
