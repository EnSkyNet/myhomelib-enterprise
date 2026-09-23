package com.myhomelibcorp.converter.toolchain.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceCleanerTest {
    @TempDir Path tempDir;

    @Test
    void deletesOrdinaryWorkspaceTree() throws Exception {
        Path root = Files.createDirectories(tempDir.resolve("workspace/input"));
        Files.writeString(root.resolve("book.tmp"), "data");
        Path workspace = tempDir.resolve("workspace");

        WorkspaceCleaner.deleteRecursively(workspace);

        assertThat(workspace).doesNotExist();
    }

    @Test
    void deletesRootSymlinkWithoutTraversingItWhenPlatformAllowsSymlinks() throws Exception {
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep.txt"), "keep");
        Path link = tempDir.resolve("workspace-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            return;
        }

        WorkspaceCleaner.deleteRecursively(link);

        assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.readString(sentinel)).isEqualTo("keep");
    }
}
