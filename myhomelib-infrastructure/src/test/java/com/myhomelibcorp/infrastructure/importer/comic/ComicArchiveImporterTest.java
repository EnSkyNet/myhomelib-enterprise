package com.myhomelibcorp.infrastructure.importer.comic;

import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ComicArchiveImporterTest {
    @TempDir Path tempDir;

    @Test
    void importsWholeCbzAsOneBookInsteadOfContainedImages() throws Exception {
        Path cbz = tempDir.resolve("Volume 01.cbz");
        Files.write(cbz, new byte[]{1, 2, 3});
        ArchiveReader reader = mock(ArchiveReader.class);
        when(reader.listEntries(cbz)).thenReturn(List.of("10.jpg", "2.jpg", "1.jpg", "notes.txt"));
        ComicArchiveImporter importer = new ComicArchiveImporter(reader);

        var books = importer.importBooks(cbz).toList();

        assertThat(books).hasSize(1);
        assertThat(books.getFirst().getTitle()).isEqualTo("Volume 01");
        assertThat(books.getFirst().getFileName()).isEqualTo("Volume 01.cbz");
        assertThat(books.getFirst().getArchiveEntry()).isBlank();
        assertThat(importer.countBooks(cbz)).isEqualTo(1);
    }

    @Test
    void emptyComicArchiveIsNotCountedAsBook() throws Exception {
        Path cbr = tempDir.resolve("empty.cbr");
        Files.write(cbr, new byte[]{1});
        ArchiveReader reader = mock(ArchiveReader.class);
        when(reader.listEntries(cbr)).thenReturn(List.of("readme.txt"));
        ComicArchiveImporter importer = new ComicArchiveImporter(reader);

        assertThat(importer.countBooks(cbr)).isZero();
    }
}
