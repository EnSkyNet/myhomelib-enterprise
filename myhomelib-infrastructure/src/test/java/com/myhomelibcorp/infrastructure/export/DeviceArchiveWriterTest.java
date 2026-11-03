package com.myhomelibcorp.infrastructure.export;

import com.myhomelibcorp.application.dto.ExportRequest;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeviceArchiveWriterTest {
    @TempDir Path temp;
    private final DeviceArchiveWriter writer = new DeviceArchiveWriter();

    @Test
    void sevenZMaximumCanBeReadWithoutExternalArchiver() throws Exception {
        Path root = prepareBooks();
        Path output = temp.resolve("series.7z");
        writer.pack(root, output, ExportRequest.ArchiveFormat.SEVEN_Z,
                ExportRequest.CompressionLevel.MAXIMUM, () -> false);

        Map<String, String> contents = new HashMap<>();
        try (SevenZFile archive = SevenZFile.builder()
                .setPath(output).setMaxMemoryLimitKiB(256 * 1024).get()) {
            org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                byte[] bytes = new byte[(int) entry.getSize()];
                int off = 0;
                while (off < bytes.length) {
                    int n = archive.read(bytes, off, bytes.length - off);
                    if (n < 0) throw new java.io.EOFException("Incomplete 7z entry");
                    off += n;
                }
                contents.put(entry.getName(), new String(bytes, StandardCharsets.UTF_8));
            }
        }
        assertThat(contents).containsEntry("author/01.fb2", "book-one")
                .containsEntry("author/02.fb2", "book-two").hasSize(2);
    }

    @Test
    void zipPreservesUnicodePathAndFullPayload() throws Exception {
        Path root = prepareBooks();
        Path output = temp.resolve("books.zip");
        writer.pack(root, output, ExportRequest.ArchiveFormat.ZIP,
                ExportRequest.CompressionLevel.FAST, () -> false);
        try (ZipFile archive = new ZipFile(output.toFile(), StandardCharsets.UTF_8)) {
            assertThat(archive.size()).isEqualTo(2);
            try (var stream = archive.getInputStream(archive.getEntry("author/02.fb2"))) {
                assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("book-two");
            }
        }
    }

    @Test
    void cancellationAndUnsafeLinksNeverPublishArchive() throws Exception {
        Path root = prepareBooks();
        Path cancelTarget = temp.resolve("cancel.7z");
        assertThatThrownBy(() -> writer.pack(root, cancelTarget,
                ExportRequest.ArchiveFormat.SEVEN_Z, ExportRequest.CompressionLevel.MAXIMUM,
                () -> true)).isInstanceOf(InterruptedIOException.class);
        assertThat(cancelTarget).doesNotExist();
    }

    @Test
    void refusesOutputInsideInputTreeAndExistingTarget() throws Exception {
        Path root = prepareBooks();
        assertThatThrownBy(() -> writer.pack(root, root.resolve("bundle.zip"),
                ExportRequest.ArchiveFormat.ZIP, ExportRequest.CompressionLevel.MAXIMUM,
                () -> false)).isInstanceOf(java.io.IOException.class);
        Path preexisting = Files.writeString(temp.resolve("existing.zip"), "original");
        assertThatThrownBy(() -> writer.pack(root, preexisting,
                ExportRequest.ArchiveFormat.ZIP, ExportRequest.CompressionLevel.MAXIMUM,
                () -> false)).isInstanceOf(java.io.IOException.class);
        assertThat(Files.readString(preexisting)).isEqualTo("original");
    }

    private Path prepareBooks() throws Exception {
        Path root = Files.createDirectory(temp.resolve("staged"));
        Path shelf = Files.createDirectory(root.resolve("author"));
        Files.writeString(shelf.resolve("01.fb2"), "book-one");
        Files.writeString(shelf.resolve("02.fb2"), "book-two");
        return root;
    }
}
