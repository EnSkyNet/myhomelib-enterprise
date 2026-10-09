package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverLocator;
import com.myhomelibcorp.infrastructure.image.EpubCoverParser;
import com.myhomelibcorp.infrastructure.image.FallbackCoverRenderer;
import com.myhomelibcorp.infrastructure.image.Fb2CoverParser;
import com.myhomelibcorp.infrastructure.image.MobiCoverParser;
import com.myhomelibcorp.infrastructure.image.PdfCoverParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoverReaderImplArchiveIdentityTest {

    @TempDir Path tempDir;

    @Test
    void resolvesServerRenamedEntryByUniqueBookIdToken() throws Exception {
        Path archive = Files.write(tempDir.resolve("729586.zip"), new byte[]{1});
        CoverLocator locator = mock(CoverLocator.class);
        ZipArchiveReader archiveReader = mock(ZipArchiveReader.class);
        Fb2CoverParser fb2 = mock(Fb2CoverParser.class);
        byte[] expected = new byte[]{4, 5, 6};
        String actual = "Dornichev_Demon-Pozhiratel-dush-01-18.729586.fb2";

        when(locator.locateCoverFile(any())).thenReturn(Optional.of(archive));
        when(archiveReader.isArchive(archive)).thenReturn(true);
        when(archiveReader.listEntries(archive)).thenReturn(List.of(actual, "other.123.fb2"));
        when(archiveReader.readEntry(archive, actual)).thenReturn(Optional.of(new ByteArrayInputStream(new byte[]{1, 2})));
        when(fb2.parseToBytes(any(java.io.InputStream.class))).thenReturn(expected);

        CoverReaderImpl reader = reader(locator, archiveReader, fb2);
        BookDto book = BookDto.builder()
                .id("729586")
                .title("Демон")
                .fileName("729586.zip")
                .archiveEntry("legacy-name.fb2")
                .build();

        assertThat(reader.readCover(book)).containsExactly(expected);
        verify(archiveReader).readEntry(archive, actual);
        verify(archiveReader, never()).findFirstEntry(eq(archive), any());
    }

    @Test
    void refusesArbitraryCoverWhenRequestedEntryIsAmbiguous() throws Exception {
        Path archive = Files.write(tempDir.resolve("shared.zip"), new byte[]{1});
        CoverLocator locator = mock(CoverLocator.class);
        ZipArchiveReader archiveReader = mock(ZipArchiveReader.class);
        Fb2CoverParser fb2 = mock(Fb2CoverParser.class);

        when(locator.locateCoverFile(any())).thenReturn(Optional.of(archive));
        when(archiveReader.isArchive(archive)).thenReturn(true);
        when(archiveReader.listEntries(archive)).thenReturn(List.of(
                "author-one.101.fb2", "author-two.202.fb2", "cover.jpg"));

        CoverReaderImpl reader = reader(locator, archiveReader, fb2);
        BookDto book = BookDto.builder()
                .id("999")
                .title("Missing book")
                .fileName("shared.zip")
                .archiveEntry("missing.999.fb2")
                .build();

        assertThat(reader.readCover(book)).isNull();
        verify(archiveReader, never()).readEntry(eq(archive), anyString());
        verify(archiveReader, never()).findFirstEntry(eq(archive), any());
    }

    private static CoverReaderImpl reader(CoverLocator locator, ZipArchiveReader archiveReader, Fb2CoverParser fb2) {
        return new CoverReaderImpl(locator, archiveReader, fb2,
                mock(EpubCoverParser.class), mock(MobiCoverParser.class), mock(PdfCoverParser.class),
                mock(FallbackCoverRenderer.class));
    }
}
