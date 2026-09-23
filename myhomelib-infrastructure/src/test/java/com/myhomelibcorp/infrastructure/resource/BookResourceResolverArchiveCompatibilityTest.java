package com.myhomelibcorp.infrastructure.resource;

import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.infrastructure.cover.ZipArchiveReader;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BookResourceResolverArchiveCompatibilityTest {

    @TempDir
    Path tempDir;

    @Test
    void readsUniqueServerRenamedEntryAfterCompatibilityResolution() throws Exception {
        Path archive = tempDir.resolve("bundle.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "server-123-mybook.fb2", "resolved");
            put(zip, "other-volume.fb2", "other");
        }

        BookResourceResolver resolver = new BookResourceResolver(new ZipArchiveReader());

        assertThat(resolver.locateBookFile("bundle.zip", "", tempDir.toString(), "mybook.fb2"))
                .contains(archive);

        Optional<InputStream> opened = resolver.readBookData(
                "bundle.zip", "", tempDir.toString(), "mybook.fb2");

        assertThat(opened).isPresent();
        try (InputStream in = opened.orElseThrow()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("resolved");
        }
    }

    @Test
    void resolverDependsOnArchiveReaderPortAndEnumeratesOncePerRead() throws Exception {
        Path archive = tempDir.resolve("bundle.7z");
        Files.writeString(archive, "placeholder");

        ArchiveReader reader = mock(ArchiveReader.class);
        when(reader.listEntries(archive)).thenReturn(List.of("nested\\mybook.fb2", "other.fb2"));
        when(reader.readEntry(archive, "nested\\mybook.fb2"))
                .thenReturn(Optional.of(new ByteArrayInputStream("resolved".getBytes(StandardCharsets.UTF_8))));

        BookResourceResolver resolver = new BookResourceResolver(reader);
        Optional<InputStream> opened = resolver.readBookData(
                "bundle.7z", "", tempDir.toString(), "/nested/mybook.fb2");

        assertThat(opened).isPresent();
        try (InputStream in = opened.orElseThrow()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("resolved");
        }
        verify(reader, times(1)).listEntries(archive);
        verify(reader, times(1)).readEntry(archive, "nested\\mybook.fb2");
        verifyNoMoreInteractions(reader);
        assertThat(BookResourceResolver.class.getDeclaredField("archiveReader").getType())
                .isEqualTo(ArchiveReader.class);
    }

    @Test
    void locateCompatibilityResolutionEnumeratesArchiveOnlyOnce() {
        Path archive = tempDir.resolve("legacy.rar");
        try {
            Files.writeString(archive, "placeholder");
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        ArchiveReader reader = mock(ArchiveReader.class);
        when(reader.listEntries(archive)).thenReturn(List.of("server-123-mybook.fb2", "other-volume.fb2"));
        BookResourceResolver resolver = new BookResourceResolver(reader);

        assertThat(resolver.locateBookFile("legacy.rar", "", tempDir.toString(), "mybook.fb2"))
                .contains(archive);
        verify(reader, times(1)).listEntries(archive);
        verifyNoMoreInteractions(reader);
    }


    @Test
    void locateBookContainerDoesNotEnumerateArchiveMembers() throws Exception {
        Path archive = tempDir.resolve("bundle.7z");
        Files.writeString(archive, "placeholder");
        ArchiveReader reader = mock(ArchiveReader.class);
        BookResourceResolver resolver = new BookResourceResolver(reader);
        Book book = archiveBook("bundle.7z", "/nested/book.fb2");

        assertThat(resolver.locateBookContainer(book)).contains(archive);
        verifyNoInteractions(reader);
    }

    @Test
    void exactUnicodeArchiveEntryWinsOverLegacyEscapedEquivalent() throws Exception {
        Path archive = tempDir.resolve("unicode-collision.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "books/#U0412#U043e#U0437#U0432#U0440#U0430#U0449#U0435#U043d#U0438#U0435_1.fb2", "legacy");
            put(zip, "books/Возвращение_1.fb2", "exact");
        }

        BookResourceResolver resolver = new BookResourceResolver(new ZipArchiveReader());
        Optional<InputStream> opened = resolver.readBookData(
                "unicode-collision.zip", "", tempDir.toString(), "books/Возвращение_1.fb2");

        assertThat(opened).isPresent();
        try (InputStream in = opened.orElseThrow()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("exact");
        }
    }

    @Test
    void exactLegacyEscapedArchiveEntryWinsOverDecodedEquivalent() throws Exception {
        Path archive = tempDir.resolve("legacy-collision.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            put(zip, "books/Возвращение_1.fb2", "unicode");
            put(zip, "books/#U0412#U043e#U0437#U0432#U0440#U0430#U0449#U0435#U043d#U0438#U0435_1.fb2", "exact-legacy");
        }

        BookResourceResolver resolver = new BookResourceResolver(new ZipArchiveReader());
        String requested = "books/#U0412#U043e#U0437#U0432#U0440#U0430#U0449#U0435#U043d#U0438#U0435_1.fb2";
        Optional<InputStream> opened = resolver.readBookData(
                "legacy-collision.zip", "", tempDir.toString(), requested);

        assertThat(opened).isPresent();
        try (InputStream in = opened.orElseThrow()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("exact-legacy");
        }
    }

    @Test
    void materializeArchiveBookEntryResolvesExactPriorityOnceThenStreamsActualMember() throws Exception {
        Path archive = tempDir.resolve("bundle.7z");
        Files.writeString(archive, "placeholder");
        Path target = tempDir.resolve("reader.fb2");
        ArchiveReader reader = mock(ArchiveReader.class);
        when(reader.listEntries(archive)).thenReturn(List.of("server-123-book.fb2", "nested\\book.fb2"));
        when(reader.materializeEntry(eq(archive), eq("nested\\book.fb2"), eq(target), eq(4096L), any()))
                .thenReturn(true);
        BookResourceResolver resolver = new BookResourceResolver(reader);

        assertThat(resolver.materializeArchiveBookEntry(archiveBook("bundle.7z", "/nested/book.fb2"),
                archive, target, 4096L, () -> false)).contains("nested\\book.fb2");

        verify(reader, times(1)).listEntries(archive);
        verify(reader, times(1)).materializeEntry(eq(archive), eq("nested\\book.fb2"), eq(target), eq(4096L), any());
        verifyNoMoreInteractions(reader);
    }

    @Test
    void materializeArchiveBookEntryKeepsUniqueLegacyServerRenamedFallback() throws Exception {
        Path archive = tempDir.resolve("legacy.rar");
        Files.writeString(archive, "placeholder");
        Path target = tempDir.resolve("legacy-reader.fb2");
        ArchiveReader reader = mock(ArchiveReader.class);
        when(reader.listEntries(archive)).thenReturn(List.of("Romanovich.586491.fb2", "other-volume.fb2"));
        when(reader.materializeEntry(eq(archive), eq("Romanovich.586491.fb2"), eq(target), eq(8192L), any()))
                .thenReturn(true);
        BookResourceResolver resolver = new BookResourceResolver(reader);

        assertThat(resolver.materializeArchiveBookEntry(archiveBook("legacy.rar", "586491.fb2"),
                archive, target, 8192L, () -> false)).contains("Romanovich.586491.fb2");

        verify(reader, times(1)).listEntries(archive);
        verify(reader, times(1)).materializeEntry(eq(archive), eq("Romanovich.586491.fb2"), eq(target), eq(8192L), any());
        verifyNoMoreInteractions(reader);
    }

    private Book archiveBook(String archiveName, String entryName) {
        return Book.builder()
                .title("Book")
                .file(new BookFile(archiveName, "", entryName, 0, tempDir.toString()))
                .build();
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
