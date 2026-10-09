package com.myhomelibcorp.infrastructure.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic byte-truncation corpus for the no-follow ZIP boundary. */
class SafeZipMalformedMutationCorpusTest {
    @Test
    void truncatedCentralDirectoryMutationsFailClosed(@TempDir Path dir) throws Exception {
        Path valid = dir.resolve("valid.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(valid), StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("книга.fb2"));
            out.write("payload".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(valid);
        try (SafeZipArchive archive = SafeZipArchive.openDetected(valid, "mutation baseline")) {
            assertThat(archive.zip().getEntry("книга.fb2")).isNotNull();
        }

        for (int length : mutationLengths(bytes.length)) {
            Path mutated = dir.resolve("truncated-" + length + ".zip");
            Files.write(mutated, java.util.Arrays.copyOf(bytes, length));
            assertThatThrownBy(() -> {
                try (SafeZipArchive ignored = SafeZipArchive.openDetected(mutated, "mutated ZIP")) { }
            }).as("truncated ZIP length %s/%s", length, bytes.length)
                    .isInstanceOfAny(java.io.IOException.class, IllegalArgumentException.class);
        }
    }

    private static List<Integer> mutationLengths(int size) {
        List<Integer> values = new ArrayList<>();
        for (int candidate : new int[]{0, 1, 4, 8, size / 3, size / 2, Math.max(0, size - 22), Math.max(0, size - 1)}) {
            if (candidate >= 0 && candidate < size && !values.contains(candidate)) values.add(candidate);
        }
        return values;
    }
}
