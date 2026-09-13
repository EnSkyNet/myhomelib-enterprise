package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ResolveBookContentUseCaseArchiveCompatibilityTest {

    @TempDir
    Path tempDir;

    @Test
    void catalogueArchiveEntryUsesFallbackAwareBookReadInsteadOfLiteralArchiveRead() throws Exception {
        BookResourcePort resources = mock(BookResourcePort.class);
        Path archive = tempDir.resolve("bundle.zip");
        BookDto book = BookDto.builder()
                .fileName("bundle.zip")
                .folder("")
                .collectionRoot(tempDir.toString())
                .archiveEntry("catalog-name.fb2")
                .build();

        when(resources.locateBookFile("bundle.zip", "", tempDir.toString(), "catalog-name.fb2"))
                .thenReturn(Optional.of(archive));
        when(resources.isArchive(archive.toString())).thenReturn(true);
        when(resources.readBookData("bundle.zip", "", tempDir.toString(), "catalog-name.fb2"))
                .thenReturn(Optional.of(new ByteArrayInputStream("book".getBytes(StandardCharsets.UTF_8))));

        ResolveBookContentUseCase useCase = new ResolveBookContentUseCase(resources);
        ResolvedBookContent resolved = useCase.execute(
                book, ResolveBookContentUseCase.READER_EXTENSIONS);
        try {
            assertThat(resolved.temporary()).isTrue();
            assertThat(java.nio.file.Files.readString(resolved.path())).isEqualTo("book");
            verify(resources).readBookData("bundle.zip", "", tempDir.toString(), "catalog-name.fb2");
            verify(resources, never()).readArchiveEntry(archive, "catalog-name.fb2");
        } finally {
            java.nio.file.Files.deleteIfExists(resolved.path());
        }
    }
    @Test
    void readerNativeComicArchiveIsReturnedWhole() throws Exception {
        BookResourcePort resources = mock(BookResourcePort.class);
        Path comic = tempDir.resolve("issue.cbz");
        BookDto book = BookDto.builder()
                .fileName("issue.cbz")
                .folder("")
                .collectionRoot(tempDir.toString())
                .archiveEntry("")
                .build();

        when(resources.locateBookFile("issue.cbz", "", tempDir.toString(), ""))
                .thenReturn(Optional.of(comic));
        when(resources.isArchive(comic.toString())).thenReturn(true);

        ResolveBookContentUseCase useCase = new ResolveBookContentUseCase(resources);
        ResolvedBookContent resolved = useCase.execute(book, ResolveBookContentUseCase.READER_EXTENSIONS);

        assertThat(resolved.path()).isEqualTo(comic);
        assertThat(resolved.temporary()).isFalse();
        verify(resources, never()).listArchiveEntries(any());
        verify(resources, never()).readArchiveEntry(any(), anyString());
    }

}
