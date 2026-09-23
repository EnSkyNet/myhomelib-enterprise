package com.myhomelibcorp.epub.optimizer.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EpubPathSafetyTest {
    @TempDir Path tempDir;

    @Test
    void createsMissingTargetParentsWithoutFollowingLinks() throws Exception {
        Path target = tempDir.resolve("safe/a/b/book.epub");

        Path prepared = EpubPathSafety.prepareTarget(target, "target");

        assertThat(prepared).isEqualTo(target.toAbsolutePath().normalize());
        assertThat(Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS)).isTrue();
    }

    @Test
    void rejectsSymlinkInSourceParentWhenPlatformAllowsSymlinks() throws Exception {
        Path real = Files.createDirectory(tempDir.resolve("real"));
        Path file = Files.writeString(real.resolve("book.epub"), "x");
        Path link = tempDir.resolve("link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }

        Path throughLink = link.resolve(file.getFileName());
        assertThatThrownBy(() -> EpubPathSafety.requireSafeRegularFile(throughLink, "source"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void rejectsSymlinkAsExistingTargetWhenPlatformAllowsSymlinks() throws Exception {
        Path real = Files.writeString(tempDir.resolve("real.epub"), "x");
        Path target = tempDir.resolve("target.epub");
        try {
            Files.createSymbolicLink(target, real);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }

        assertThatThrownBy(() -> EpubPathSafety.prepareTarget(target, "target"))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("symbolic link");
    }

    @Test
    void freshOutputRefusesExistingRegularFile() throws Exception {
        Path target = Files.writeString(tempDir.resolve("existing.epub"), "keep");

        assertThatThrownBy(() -> EpubPathSafety.openFreshOutputStream(target, "fresh output"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(target)).isEqualTo("keep");
    }

    @Test
    void moveNoReplaceRefusesExistingTargetAndPreservesBothFiles() throws Exception {
        Path source = Files.writeString(tempDir.resolve("source.epub"), "source");
        Path target = Files.writeString(tempDir.resolve("target.epub"), "target");

        assertThatThrownBy(() -> EpubPathSafety.moveNoReplace(source, target, "publication"))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(source)).isEqualTo("source");
        assertThat(Files.readString(target)).isEqualTo("target");
    }
}
