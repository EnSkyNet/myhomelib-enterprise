package com.myhomelibcorp.organizer.fs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LibraryOperationWorkspaceTest {
    @TempDir Path tempDir;

    @Test
    void deleteTreeDeletesOnlyRootSymlinkWhenPlatformSupportsIt() throws Exception {
        Path real = Files.createDirectory(tempDir.resolve("real"));
        Path protectedFile = Files.writeString(real.resolve("keep.txt"), "keep");
        Path link = tempDir.resolve("workspace-link");
        try {
            Files.createSymbolicLink(link, real);
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        LibraryOperationWorkspace.deleteTree(link);

        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.exists(protectedFile, LinkOption.NOFOLLOW_LINKS)).isTrue();
    }
    @Test
    void deleteTreeRefusesRootReplacedByRegularFile() throws Exception {
        Path replacement = Files.writeString(tempDir.resolve("workspace-replacement"), "FOREIGN");

        assertThatThrownBy(() -> LibraryOperationWorkspace.deleteTree(replacement))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("replaced by a non-directory");

        assertThat(Files.readString(replacement)).isEqualTo("FOREIGN");
    }

    @Test
    void deleteTreeRefusesSymlinkedParentAndPreservesOutsideTreeWhenSupported() throws Exception {
        Path outside = Files.createDirectories(tempDir.resolve("outside/workspace"));
        Path protectedFile = Files.writeString(outside.resolve("keep.txt"), "keep");
        Path parentLink = tempDir.resolve("parent-link");
        try {
            Files.createSymbolicLink(parentLink, tempDir.resolve("outside"));
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        Path throughLink = parentLink.resolve("workspace");
        assertThatThrownBy(() -> LibraryOperationWorkspace.deleteTree(throughLink))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("symbolic link");

        assertThat(Files.exists(protectedFile, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.isSymbolicLink(parentLink)).isTrue();
    }

}
