package com.myhomelibcorp.organizer.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizerPathSafetyTest {
    @TempDir Path tempDir;

    @Test
    void rejectsChildOutsideConfiguredRoot() throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("root"));
        Path outside = Files.createDirectory(tempDir.resolve("outside")).resolve("book.epub");

        assertThatThrownBy(() -> OrganizerPathSafety.requireUnderRoot(root, outside, "target"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("escapes configured root");
    }

    @Test
    void rejectsSymbolicLinkComponentWhenPlatformAllowsSymlinks() throws Exception {
        Path real = Files.createDirectory(tempDir.resolve("real"));
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return; // Platform/test account cannot create symlinks; dedicated Windows junction gate covers that environment.
        }

        assertThatThrownBy(() -> OrganizerPathSafety.createDirectoriesNoLinks(link.resolve("child"), "target"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void freshOutputRefusesExistingRegularFile() throws Exception {
        Path target = Files.writeString(tempDir.resolve("existing.bin"), "keep");

        assertThatThrownBy(() -> OrganizerPathSafety.openFreshOutputStream(target, "fresh output"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(target)).isEqualTo("keep");
    }
}
