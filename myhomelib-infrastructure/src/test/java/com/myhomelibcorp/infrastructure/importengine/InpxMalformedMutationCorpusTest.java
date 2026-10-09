package com.myhomelibcorp.infrastructure.importengine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Deterministic mutation corpus for fail-closed INPX container handling. */
class InpxMalformedMutationCorpusTest {
    private static final char D = 0x04;

    @Test
    void truncatedContainerMutationsAreRejectedWithoutPartialRecords(@TempDir Path dir) throws Exception {
        Path valid = dir.resolve("valid.inpx");
        String row = String.join(String.valueOf(D),
                "Doe,John,", "prose", "Title", "", "0", "book", "10", "id-1", "0", "fb2", "2026", "en", "") + "\n";
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(valid), StandardCharsets.UTF_8)) {
            out.putNextEntry(new ZipEntry("main.inp"));
            out.write(row.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        byte[] bytes = Files.readAllBytes(valid);
        assertThat(new InpxReader().count(valid)).isEqualTo(1);
        for (int length : mutationLengths(bytes.length)) {
            Path mutated = dir.resolve("truncated-" + length + ".inpx");
            Files.write(mutated, java.util.Arrays.copyOf(bytes, length));
            assertThatThrownBy(() -> new InpxReader().count(mutated))
                    .as("truncated INPX length %s/%s", length, bytes.length)
                    .isInstanceOfAny(UncheckedIOException.class, IllegalArgumentException.class);
        }
    }

    private static List<Integer> mutationLengths(int size) {
        List<Integer> values = new ArrayList<>();
        for (int candidate : new int[]{0, 1, 4, 8, size / 4, size / 2, Math.max(0, size - 32), Math.max(0, size - 1)}) {
            if (candidate >= 0 && candidate < size && !values.contains(candidate)) values.add(candidate);
        }
        return values;
    }
}
