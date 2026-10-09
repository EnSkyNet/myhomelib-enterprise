package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.infrastructure.archive.SafeZipArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class InpxReaderResourceTest {
    @Test
    void rejectedArchiveClosesItsAlreadyOpenedSafeArchive(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("rejected.inpx");
        Files.write(path, new byte[]{0});
        Path safePath = path.toAbsolutePath().normalize();
        SafeZipArchive opened = mock(SafeZipArchive.class);
        ZipFile zip = mock(ZipFile.class);
        ZipArchiveEntry oversized = new ZipArchiveEntry("catalog.inp");
        oversized.setSize(ArchiveSafetyLimits.MAX_ENTRY_BYTES + 1);
        when(opened.zip()).thenReturn(zip);
        when(zip.getEntries()).thenAnswer(call -> Collections.enumeration(List.of(oversized)));
        try (var support = mockStatic(SafeZipArchive.class)) {
            support.when(() -> SafeZipArchive.openDetected(safePath, "INPX source")).thenReturn(opened);
            var failure = assertThrows(UncheckedIOException.class, () -> new InpxReader().read(path));
            assertThat(failure.getCause()).isInstanceOf(IOException.class).hasMessageContaining("too large");
            verify(opened).close();
        }
    }

    @Test
    void rejectsArchiveWhoseDeclaredEntriesExceedCumulativeSafetyLimit(@TempDir Path directory) throws Exception {
        Path path = directory.resolve("cumulative-bomb.inpx");
        Files.write(path, new byte[]{0});
        Path safePath = path.toAbsolutePath().normalize();
        SafeZipArchive opened = mock(SafeZipArchive.class);
        ZipFile zip = mock(ZipFile.class);
        long entryBytes = 400L * 1024L * 1024L;
        long compressedBytes = 5L * 1024L * 1024L;
        List<ZipArchiveEntry> entries = java.util.stream.IntStream.range(0, 3)
                .mapToObj(i -> {
                    ZipArchiveEntry entry = new ZipArchiveEntry("catalog-" + i + ".inp");
                    entry.setSize(entryBytes);
                    entry.setCompressedSize(compressedBytes);
                    return entry;
                })
                .toList();
        when(opened.zip()).thenReturn(zip);
        when(zip.getEntries()).thenAnswer(call -> Collections.enumeration(entries));
        when(zip.getInputStream(any(ZipArchiveEntry.class))).thenAnswer(call -> InputStream.nullInputStream());

        try (var support = mockStatic(SafeZipArchive.class)) {
            support.when(() -> SafeZipArchive.openDetected(safePath, "INPX source")).thenReturn(opened);

            var failure = assertThrows(UncheckedIOException.class, () -> new InpxReader().count(path));

            assertThat(failure.getCause())
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("cumulative")
                    .hasMessageContaining("safety limit");
            verify(opened).close();
        }
    }

    @Test
    void standaloneInpSkipsBlankLinesLikeTheArchiveIterator(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("catalog.inp");
        Files.writeString(file, "\n  \nAuthor|sf|First|||one|12|1|0|fb2|2026-01-01|uk|\n\n"
                + "Author|sf|Second|||two|13|2|0|fb2|2026-01-01|uk|\n \n");
        var records = new InpxReader().read(file);
        try {
            assertThat(records.next().field("TITLE")).isEqualTo("First");
            assertThat(records.next().field("TITLE")).isEqualTo("Second");
            assertThat(records.hasNext()).isFalse();
        } finally {
            InpxReader.closeIterator(records);
        }
    }
}
