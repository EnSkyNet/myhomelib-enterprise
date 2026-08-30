package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DownloadPayloadValidatorTest {
    @TempDir Path temp;

    @Test
    void highReliabilityZipScansCrcSizeAndFb2Payload() throws Exception {
        Path zip = temp.resolve("books.zip");
        writeZip(zip, new Entry("book.fb2", "<?xml version=\"1.0\"?><FictionBook><body/></FictionBook>"));
        ArchiveReader reader = reader(List.of("book.fb2"), "<?xml version=\"1.0\"?><FictionBook/>");

        assertThatCode(() -> validator(reader).validate(zip, zip, book("book.fb2"), true))
                .doesNotThrowAnyException();
    }

    @Test
    void highReliabilityModeRejectsCaseInsensitiveDuplicateEntryNames() throws Exception {
        Path zip = temp.resolve("duplicate.zip");
        writeZip(zip,
                new Entry("book.fb2", "<FictionBook/>"),
                new Entry("BOOK.FB2", "<FictionBook/>"));
        ArchiveReader reader = reader(List.of("book.fb2", "BOOK.FB2"), "<FictionBook/>");

        assertThatThrownBy(() -> validator(reader).validate(zip, zip, book("book.fb2"), true))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("дубльоване");
    }

    private DownloadPayloadValidator validator(ArchiveReader reader) {
        ApplicationSettingsPort settings = mock(ApplicationSettingsPort.class);
        when(settings.getBoolean(anyString(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(inv -> inv.getArgument(1));
        when(settings.getBoolean("online.archive.highReliabilityValidation", false)).thenReturn(true);
        return new DownloadPayloadValidator(reader, settings);
    }

    private ArchiveReader reader(List<String> entries, String content) throws Exception {
        ArchiveReader reader = mock(ArchiveReader.class);
        when(reader.listEntries(org.mockito.ArgumentMatchers.any())).thenReturn(entries);
        when(reader.readEntry(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> Optional.of(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
        return reader;
    }

    private BookDto book(String entry) {
        BookDto book = mock(BookDto.class);
        when(book.getArchiveEntry()).thenReturn(entry);
        when(book.getFileName()).thenReturn(entry);
        return book;
    }

    private void writeZip(Path path, Entry... entries) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Entry entry : entries) {
                out.putNextEntry(new ZipEntry(entry.name()));
                out.write(entry.content().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
    }

    private record Entry(String name, String content) { }
}
