package com.myhomelibcorp.infrastructure.importengine;

import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.archive.ZipCharsetSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class InpxReaderResourceTest {
    @Test
    void rejectedArchiveClosesItsAlreadyOpenedZipFile() throws Exception {
        Path path = Path.of("rejected.inpx");
        ZipFile zip = mock(ZipFile.class);
        ZipEntry oversized = new ZipEntry("catalog.inp");
        oversized.setSize(ArchiveSafetyLimits.MAX_ENTRY_BYTES + 1);
        when(zip.entries()).thenAnswer(call -> Collections.enumeration(List.of(oversized)));
        try (var support = mockStatic(ZipCharsetSupport.class)) {
            support.when(() -> ZipCharsetSupport.open(path)).thenReturn(zip);
            var failure = assertThrows(UncheckedIOException.class, () -> new InpxReader().read(path));
            assertThat(failure.getCause()).isInstanceOf(IOException.class).hasMessageContaining("too large");
            verify(zip).close();
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
