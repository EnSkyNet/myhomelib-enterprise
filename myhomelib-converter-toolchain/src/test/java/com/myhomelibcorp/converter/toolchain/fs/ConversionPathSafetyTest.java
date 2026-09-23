package com.myhomelibcorp.converter.toolchain.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversionPathSafetyTest {
    @TempDir Path tempDir;

    @Test
    void acceptsRegularFileUnderNormalDirectories() throws Exception {
        Path dir = ConversionPathSafety.createDirectoriesNoLinks(tempDir.resolve("a/b"), "workspace");
        Path file = dir.resolve("book.epub");
        Files.writeString(file, "data");

        assertThat(ConversionPathSafety.requireSafeRegularFile(file, "output")).isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    void rejectsDirectSymbolicLinkWhenPlatformSupportsIt() throws Exception {
        Path real = tempDir.resolve("real.bin");
        Files.writeString(real, "data");
        Path link = tempDir.resolve("link.bin");
        try {
            Files.createSymbolicLink(link, real.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        assertThatThrownBy(() -> ConversionPathSafety.requireSafeRegularFile(link, "output"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsTargetWhoseParentIsSymbolicLinkWhenPlatformSupportsIt() throws Exception {
        Path realDir = Files.createDirectory(tempDir.resolve("real"));
        Path linkedDir = tempDir.resolve("linked");
        try {
            Files.createSymbolicLink(linkedDir, realDir.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        assertThatThrownBy(() -> ConversionPathSafety.prepareTarget(linkedDir.resolve("book.epub"), "target"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void freshOutputRefusesExistingRegularFile() throws Exception {
        Path target = Files.writeString(tempDir.resolve("existing.bin"), "keep");

        assertThatThrownBy(() -> ConversionPathSafety.openFreshOutputStream(target, "fresh output"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(target)).isEqualTo("keep");
    }

    @Test
    void moveNoReplaceRefusesExistingTargetAndPreservesBothFiles() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.bin"), "source");
        Path target = Files.writeString(tempDir.resolve("target.bin"), "target");

        assertThatThrownBy(() -> ConversionPathSafety.moveNoReplace(source, target, "publication"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(source)).isEqualTo("source");
        assertThat(Files.readString(target)).isEqualTo("target");
    }
}
