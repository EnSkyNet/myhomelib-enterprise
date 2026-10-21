package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtomicFileSupportStreamingTest {
    @TempDir Path directory;

    @Test
    void writesManyChunksIntoOneVerifiedAtomicFile() throws Exception {
        Path target = directory.resolve("large-export.txt");
        Files.writeString(target, "OLD", StandardCharsets.UTF_8);

        AtomicFileSupport.writeWithWriterReplacing(target, StandardCharsets.UTF_8, out -> {
            for (int i = 0; i < 25_000; i++) {
                out.append("book-").append(Integer.toString(i)).append('\n');
            }
        });

        String result = Files.readString(target, StandardCharsets.UTF_8);
        assertThat(result).startsWith("book-0\n");
        assertThat(result).endsWith("book-24999\n");
        assertThat(result.lines().count()).isEqualTo(25_000L);
        assertThat(stagingFiles()).isZero();
    }

    @Test
    void failedWriterLeavesPreviousFileUntouchedAndRemovesOwnedPartialStaging() throws Exception {
        Path target = directory.resolve("original.txt");
        Files.writeString(target, "KEEP", StandardCharsets.UTF_8);

        assertThatThrownBy(() -> AtomicFileSupport.writeWithWriterReplacing(
                target, StandardCharsets.UTF_8, out -> {
                    out.write("partially written");
                    throw new IOException("intentional exporter failure");
                })).isInstanceOf(IOException.class).hasMessageContaining("intentional exporter failure");

        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("KEEP");
        assertThat(stagingFiles()).isZero();
    }

    @Test
    void rejectsSymlinkDestinationWithoutTouchingExternalBytes() throws Exception {
        Path external = directory.resolve("external.txt");
        Files.writeString(external, "FOREIGN", StandardCharsets.UTF_8);
        Path target = directory.resolve("target.txt");
        try { Files.createSymbolicLink(target, external); }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) { return; }

        assertThatThrownBy(() -> AtomicFileSupport.writeWithWriterReplacing(
                target, StandardCharsets.UTF_8, out -> out.write("OVERWRITE")))
                .isInstanceOf(IOException.class);

        assertThat(Files.readString(external, StandardCharsets.UTF_8)).isEqualTo("FOREIGN");
        assertThat(Files.isSymbolicLink(target)).isTrue();
    }

    private long stagingFiles() throws IOException {
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith(".mhl-write-")).count();
        }
    }
}
