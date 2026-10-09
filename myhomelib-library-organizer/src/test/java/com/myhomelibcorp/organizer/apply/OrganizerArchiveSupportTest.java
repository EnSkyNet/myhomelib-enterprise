package com.myhomelibcorp.organizer.apply;

import com.myhomelibcorp.shared.archive.SafeZipSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizerArchiveSupportTest {
    @TempDir Path tempDir;

    @Test
    void hashesSizesAndCopiesTheSameValidatedEntry() throws Exception {
        byte[] payload = "organizer-entry".getBytes(StandardCharsets.UTF_8);
        Path archive = zip("books/item.fb2", payload);

        assertThat(OrganizerArchiveSupport.size(archive, "books/item.fb2")).isEqualTo(payload.length);
        assertThat(OrganizerArchiveSupport.sha256(archive, "books/item.fb2"))
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload)));

        try (var zip = SafeZipSupport.openDetected(archive, "test archive")) {
            var entry = OrganizerArchiveSupport.requireEntry(zip, "books/item.fb2");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            assertThat(OrganizerArchiveSupport.copyEntry(zip, entry, output, () -> false)).isEqualTo(payload.length);
            assertThat(output.toByteArray()).containsExactly(payload);
        }
    }

    @Test
    void rejectsTraversalNamesAndHonorsCancellationBeforePublishingBytes() throws Exception {
        assertThat(OrganizerArchiveSupport.unsafeEntryName("../escape.fb2")).isTrue();
        assertThat(OrganizerArchiveSupport.unsafeEntryName("books/ok.fb2")).isFalse();

        Path archive = zip("books/item.fb2", new byte[128]);
        try (var zip = SafeZipSupport.openDetected(archive, "test archive")) {
            var entry = OrganizerArchiveSupport.requireEntry(zip, "books/item.fb2");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            AtomicBoolean cancelled = new AtomicBoolean(true);
            assertThatThrownBy(() -> OrganizerArchiveSupport.copyEntry(zip, entry, output, cancelled::get))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);
            assertThat(output.size()).isZero();
        }
    }

    private Path zip(String name, byte[] payload) throws Exception {
        Path archive = tempDir.resolve("fixture.zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive), StandardCharsets.UTF_8)) {
            output.putNextEntry(new ZipEntry(name));
            output.write(payload);
            output.closeEntry();
        }
        return archive;
    }
}
