package com.myhomelibcorp.infrastructure.cover;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ZipArchiveReaderTransparencyTest {

    private final ZipArchiveReader reader = new ZipArchiveReader();

    @Test
    void distinguishesMissingEntryFromCorruptArchive(@TempDir Path dir) throws Exception {
        Path valid = dir.resolve("valid.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(valid))) {
            out.putNextEntry(new ZipEntry("book.fb2"));
            out.write("<FictionBook/>".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertThat(reader.readEntry(valid, "missing.fb2")).isEmpty();

        Path corrupt = dir.resolve("corrupt.zip");
        Files.writeString(corrupt, "not a zip archive");
        assertThatThrownBy(() -> reader.listEntries(corrupt))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("corrupt.zip");
        assertThatThrownBy(() -> reader.readEntry(corrupt, "book.fb2"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("book.fb2");
    }

    @Test
    void cbzUsesZipContainerSemanticsForLazyComicPageAccess(@TempDir Path dir) throws Exception {
        Path comic = dir.resolve("issue.cbz");
        byte[] first = "page-one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "page-two".getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(comic))) {
            putZip(out, "001.jpg", first);
            putZip(out, "002.png", second);
        }

        assertThat(reader.isArchive(comic)).isTrue();
        assertThat(reader.listEntries(comic)).containsExactly("001.jpg", "002.png");
        try (InputStream in = reader.readEntry(comic, "002.png").orElseThrow()) {
            assertThat(in.readAllBytes()).isEqualTo(second);
        }
    }

    @Test
    void findFirstEntryUsesDirectArchiveTraversalInsteadOfListThenReopen(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("books.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            putZip(out, "ignore.txt", "ignore".getBytes(StandardCharsets.UTF_8));
            putZip(out, "nested/book.fb2", "payload".getBytes(StandardCharsets.UTF_8));
        }

        ZipArchiveReader direct = oldReadPathForbidden();
        Optional<InputStream> opened = direct.findFirstEntry(archive, name -> name.endsWith(".fb2"));
        assertThat(opened).isPresent();
        try (InputStream in = opened.orElseThrow()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("payload");
        }
    }

    @Test
    void findFirstEntryReadsSequentialTarWithoutListReopen(@TempDir Path dir) throws Exception {
        Path archive = createTar(dir.resolve("books.tar"), "nested/book.fb2", "tar-payload".getBytes(StandardCharsets.UTF_8));

        ZipArchiveReader direct = oldReadPathForbidden();
        Optional<InputStream> opened = direct.findFirstEntry(archive, name -> name.endsWith(".fb2"));
        assertThat(opened).isPresent();
        try (InputStream in = opened.orElseThrow()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("tar-payload");
        }
    }

    @Test
    void findFirstEntryPreservesZipCompressionRatioGuard(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("bomb-like.zip");
        byte[] highlyCompressible = new byte[2 * 1024 * 1024];
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            putZip(out, "book.fb2", highlyCompressible);
        }

        assertThatThrownBy(() -> reader.findFirstEntry(archive, name -> name.endsWith(".fb2")))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("bomb-like.zip");
    }

    @Test
    void materializeZipStreamsDirectlyAndPublishesOnlyCompletedTarget(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("books.zip");
        byte[] payload = new byte[256 * 1024];
        new java.util.Random(46L).nextBytes(payload);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            putZip(out, "nested/book.fb2", payload);
        }
        Path target = dir.resolve("reader.fb2");

        ZipArchiveReader direct = oldReadPathForbidden();
        assertThat(direct.materializeEntry(archive, "nested/book.fb2", target, payload.length + 1L, () -> false)).isTrue();
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
        assertNoStagingFiles(dir);
    }

    @Test
    void materializeRejectsEntryOverCallerLimitWithoutPublishingPartialTarget(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("limited.zip");
        byte[] payload = "x".repeat(4096).getBytes(StandardCharsets.UTF_8);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            putZip(out, "book.fb2", payload);
        }
        Path target = dir.resolve("reader.fb2");

        assertThatThrownBy(() -> reader.materializeEntry(archive, "book.fb2", target, 1024, () -> false))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("too large");
        assertThat(target).doesNotExist();
        assertNoStagingFiles(dir);
    }

    @Test
    void materializeCancellationDeletesPartialStagingAndDoesNotPublishTarget(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("cancel.zip");
        byte[] payload = new byte[256 * 1024];
        java.util.Arrays.fill(payload, (byte) 'a');
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry entry = new ZipEntry("book.fb2");
            entry.setMethod(ZipEntry.STORED);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(payload);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(payload);
            out.closeEntry();
        }
        Path target = dir.resolve("reader.fb2");
        AtomicInteger checks = new AtomicInteger();

        assertThatThrownBy(() -> reader.materializeEntry(archive, "book.fb2", target, payload.length + 1L,
                () -> checks.incrementAndGet() >= 3))
                .isInstanceOf(InterruptedIOException.class)
                .hasMessageContaining("cancelled");
        assertThat(target).doesNotExist();
        assertNoStagingFiles(dir);
    }

    @Test
    void materializePreservesZipCompressionRatioGuard(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("bomb-like.zip");
        byte[] highlyCompressible = new byte[2 * 1024 * 1024];
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(archive))) {
            putZip(out, "book.fb2", highlyCompressible);
        }
        Path target = dir.resolve("reader.fb2");

        assertThatThrownBy(() -> reader.materializeEntry(archive, "book.fb2", target, highlyCompressible.length + 1L, () -> false))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("compression ratio");
        assertThat(target).doesNotExist();
        assertNoStagingFiles(dir);
    }

    @Test
    void materializeSequentialTarDoesNotUseLegacyReadOrListPath(@TempDir Path dir) throws Exception {
        Path archive = createTar(dir.resolve("books.tar"), "nested/book.fb2", "tar-stream".getBytes(StandardCharsets.UTF_8));
        Path target = dir.resolve("tar-reader.fb2");

        ZipArchiveReader direct = oldReadPathForbidden();
        assertThat(direct.materializeEntry(archive, "nested/book.fb2", target, 1024, () -> false)).isTrue();
        assertThat(Files.readString(target)).isEqualTo("tar-stream");
        assertNoStagingFiles(dir);
    }

    @Test
    void materializeSequential7zDoesNotUseLegacyReadOrListPath(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("books.7z");
        byte[] ignored = "ignore".getBytes(StandardCharsets.UTF_8);
        byte[] payload = "seven-stream".getBytes(StandardCharsets.UTF_8);
        try (SevenZOutputFile out = new SevenZOutputFile(archive.toFile())) {
            put7z(out, "ignore.txt", ignored);
            put7z(out, "nested/book.fb2", payload);
        }
        Path target = dir.resolve("seven-reader.fb2");

        ZipArchiveReader direct = oldReadPathForbidden();
        assertThat(direct.materializeEntry(archive, "nested/book.fb2", target, 1024, () -> false)).isTrue();
        assertThat(Files.readAllBytes(target)).isEqualTo(payload);
        assertNoStagingFiles(dir);
    }

    private static ZipArchiveReader oldReadPathForbidden() {
        return new ZipArchiveReader() {
            @Override
            public java.util.List<String> listEntries(Path archivePath) {
                throw new AssertionError("streaming path must not enumerate into a List first");
            }

            @Override
            public Optional<InputStream> readEntry(Path archivePath, String entryName) {
                throw new AssertionError("streaming path must not reopen the selected entry through readEntry");
            }
        };
    }

    private static Path createTar(Path archive, String entryName, byte[] payload) throws Exception {
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
            byte[] ignored = "ignore".getBytes(StandardCharsets.UTF_8);
            TarArchiveEntry first = new TarArchiveEntry("ignore.txt");
            first.setSize(ignored.length);
            out.putArchiveEntry(first);
            out.write(ignored);
            out.closeArchiveEntry();

            TarArchiveEntry second = new TarArchiveEntry(entryName);
            second.setSize(payload.length);
            out.putArchiveEntry(second);
            out.write(payload);
            out.closeArchiveEntry();
        }
        return archive;
    }

    private static void putZip(ZipOutputStream out, String name, byte[] payload) throws Exception {
        out.putNextEntry(new ZipEntry(name));
        out.write(payload);
        out.closeEntry();
    }

    private static void put7z(SevenZOutputFile out, String name, byte[] payload) throws Exception {
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName(name);
        entry.setSize(payload.length);
        out.putArchiveEntry(entry);
        out.write(payload);
        out.closeArchiveEntry();
    }

    private static void assertNoStagingFiles(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            assertThat(stream.map(path -> path.getFileName().toString())
                    .filter(name -> name.startsWith(".mhl-archive-materialize-"))
                    .toList()).isEmpty();
        }
    }
}
